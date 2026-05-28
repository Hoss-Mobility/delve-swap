package com.hoss.delveperconfig

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Files
import java.nio.file.Path

interface RegistryBackend {
    fun get(): String
    fun set(v: String)
}

internal class SystemPropertyBackend : RegistryBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()
    override fun set(v: String) { System.setProperty(DLV_PATH_KEY, v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}

internal class IntelliJRegistryBackend : RegistryBackend {
    override fun get(): String = Registry.get(DLV_PATH_KEY).asString()
    override fun set(v: String) { Registry.get(DLV_PATH_KEY).setValue(v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}

/**
 * Writes through to BOTH the IntelliJ Registry value `dlv.path` and the
 * `dlv.path` Java System property. The Go plugin's GoRunUtil.dlv(...) reads
 * the System property (NOT the Registry) when picking the dlv binary to
 * upload to a remote target, so the System-property write is the load-bearing
 * one. The Registry write is kept for parity with the user-facing
 * Settings > Advanced view of `dlv.path`.
 *
 * Reads prefer the System-property side (matching what the Go plugin will
 * actually consult); the Registry side is a fallback for environments where
 * the bridge has populated only the Registry.
 */
class PlatformRegistryBackend(
    private val system: RegistryBackend = SystemPropertyBackend(),
    private val registry: RegistryBackend = IntelliJRegistryBackend(),
) : RegistryBackend {
    override fun get(): String = system.get().ifEmpty { registry.get() }
    override fun set(v: String) {
        registry.set(v)
        system.set(v)
    }
}

/**
 * Captures the original `dlv.path` once on the first active session and restores it once
 * the last session ends. While multiple sessions are active, the backend value is the
 * most-recently-swapped value.
 *
 * Read semantics: the Go plugin's GoRunUtil.dlv(TargetEnvironmentRequest) reads
 * System.getProperty("dlv.path") inside GoRunningState.startProcess (not the IntelliJ
 * Registry), so the PlatformRegistryBackend writes both stores to ensure GoRunUtil sees
 * our swap. Because GoRunUtil reads the property fresh on each debug start, the value
 * present at startProcess-time is what the Go plugin uses for that session.
 *
 * Known limitation: if two debug sessions are scheduled within a few hundred milliseconds
 * of each other and target different per-config paths, last-writer-wins — both sessions
 * see the second swap's value. See [docs/superpowers/plans/...-race.md] for the proposed
 * follow-up.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered
 * on next IDE startup.
 */
class RegistrySwapper(private val backend: RegistryBackend) {
    private var original: String? = null
    private val active = mutableSetOf<String>()
    private val lock = Any()

    fun swap(sessionId: String, newValue: String) {
        synchronized(lock) {
            if (sessionId in active) return  // duplicate dispatch — no-op
            if (active.isEmpty()) {
                val captured = backend.get()
                original = captured
                SentinelStore.write(captured)
            }
            active.add(sessionId)
            backend.set(newValue)
        }
    }

    fun restore(sessionId: String) {
        synchronized(lock) {
            if (!active.remove(sessionId)) return
            if (active.isEmpty()) {
                original?.let { backend.set(it) }
                original = null
                SentinelStore.clear()
            }
        }
    }
}

internal object SentinelStore {
    private val FILE: Path =
        Path.of(PathManager.getSystemPath(), "delveswap", "sentinel")

    fun write(original: String) {
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(FILE, original)
        } catch (_: Exception) { /* best effort */ }
    }

    fun clear() {
        try { Files.deleteIfExists(FILE) } catch (_: Exception) {}
    }

    fun readAndClear(): String? {
        return try {
            if (!Files.exists(FILE)) null
            else Files.readString(FILE).also { Files.deleteIfExists(FILE) }
        } catch (_: Exception) { null }
    }
}

@Service(Service.Level.APP)
class GlobalRegistrySwapper {
    val swapper = RegistrySwapper(PlatformRegistryBackend())
}

class RegistrySwapListener(private val project: Project) : ExecutionListener {

    private val log = thisLogger()
    private val swapper: RegistrySwapper
        get() = service<GlobalRegistrySwapper>().swapper

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val profile = env.runProfile
        if (profile !is com.intellij.execution.configurations.RunConfigurationBase<*>) return
        if (!GoConfigSupport.isSupported(profile)) return

        val s = DelvePathSettingsStore.get(profile)
        if (s.useGlobal || s.path.isBlank()) return

        val processEnv = collectEnv(profile)
        val expanded = PathExpansion.expand(s.path, project, processEnv)
        val validation = DelvePathValidator.validateExpanded(expanded, overrideEnabled = true)
        when (validation.severity) {
            DelvePathValidator.Severity.ERROR -> {
                notify(
                    title = "Delve path override skipped",
                    content = "${validation.message}: $expanded. Using global dlv.path.",
                )
                return
            }
            DelvePathValidator.Severity.WARN -> {
                notify(
                    title = "Delve path warning",
                    content = "${validation.message}: $expanded. Attempting launch anyway.",
                )
                // fall through to swap — OS will surface a clearer error if exec fails.
            }
            DelvePathValidator.Severity.OK -> Unit
        }

        log.info("Swapping dlv.path -> ${java.nio.file.Path.of(expanded).fileName} for executor=$executorId")
        swapper.swap(sessionKey(env), expanded)
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        swapper.restore(sessionKey(env))
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        swapper.restore(sessionKey(env))
    }

    private fun sessionKey(env: ExecutionEnvironment): String =
        env.executionId.toString()

    private fun collectEnv(profile: com.intellij.execution.configurations.RunConfigurationBase<*>): Map<String, String> {
        val base = HashMap(System.getenv())
        val custom = runCatching { profile::class.java.getMethod("getCustomEnvironment").invoke(profile) }
            .onFailure { thisLogger().debug("Could not read getCustomEnvironment from ${profile::class.java.name}", it) }
            .getOrNull() as? Map<*, *>
        custom?.forEach { (k, v) -> if (k is String && v is String) base[k] = v }
        return base
    }

    private fun notify(title: String, content: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("DelveSwap")
            .createNotification(title, content, com.intellij.notification.NotificationType.WARNING)
            .notify(project)
    }
}
