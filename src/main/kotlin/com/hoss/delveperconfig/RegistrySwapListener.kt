package com.hoss.delveperconfig

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

interface RegistryBackend {
    fun get(): String
    fun set(v: String)
}

internal class SystemPropertyBackend : RegistryBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()

    // swap() only ever sets a validated, non-blank path, so an empty value reaches set() only
    // when restoring/recovering an original that was absent — clear it rather than leaving a
    // stale empty string (the Go plugin treats empty and absent equivalently).
    override fun set(v: String) {
        if (v.isEmpty()) System.clearProperty(DLV_PATH_KEY) else System.setProperty(DLV_PATH_KEY, v)
    }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}

/**
 * Production backend used by [GlobalRegistrySwapper] and [SentinelRecovery]. Delegates to
 * [SystemPropertyBackend] — `dlv.path` is read by the Go plugin via
 * `System.getProperty("dlv.path")` only. It is NOT an IntelliJ Registry key (the Go plugin
 * never declares it as one), so attempting `Registry.get("dlv.path")` would throw
 * "Registry key dlv.path is not defined" on a freshly-installed IDE.
 */
class PlatformRegistryBackend(
    private val backend: RegistryBackend = SystemPropertyBackend(),
) : RegistryBackend by backend

/**
 * Captures the original `dlv.path` once on the first active session and restores it once
 * the last session ends. While multiple sessions are active, the backend value is the
 * most-recently-swapped value.
 *
 * Read semantics: when a debug session starts, GoRunningState.startProcess calls
 * GoRunUtil.dlv(TargetEnvironmentRequest), and that method reads
 * System.getProperty("dlv.path") to pick the dlv binary to upload. The property is read
 * fresh on each debug start, so the value present at startProcess time is what the Go
 * plugin uses for that session. PlatformRegistryBackend writes only this System property
 * — `dlv.path` is not an IntelliJ Registry key.
 *
 * Known limitation: if two debug sessions are scheduled within a short enough window
 * that both swap() calls complete before either startProcess runs, last-writer-wins —
 * both sessions see the second swap's value. A per-session bridge is tracked as a
 * follow-up plan.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered
 * on next IDE startup.
 */
class RegistrySwapper(private val backend: RegistryBackend) {
    /**
     * The `dlv.path` value present before any session swapped — captured once at
     * construction (≈ IDE startup for the app-level [GlobalRegistrySwapper]). This is what
     * the GLOBAL tier resolves to, and it never changes for the lifetime of the swapper.
     */
    val baseline: String = backend.get()
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

    fun hasActiveSessions(): Boolean = synchronized(lock) { active.isNotEmpty() }
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

        log.info("Swapping dlv.path -> $expanded for executor=$executorId")
        swapper.swap(sessionKey(env), expanded)
        env.putUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY, expanded)
    }

    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        // A "Run on target" debug fires two ExecutionEnvironments back-to-back: a fast build
        // sub-execution that terminates in ~200ms, then the real debug. The build run's
        // processTerminated restores dlv.path before the real debug reads it. The Go plugin
        // copies our CUSTOM_DELVE_PATH_KEY onto the second env, so we re-apply the override
        // here — but through the ref-counted swapper, so it is balanced by *this* env's
        // processTerminated -> restore and never leaks past the session.
        val customPath = env.getUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY)
        reapplyOnStarting(swapper, sessionKey(env), customPath)
        if (!customPath.isNullOrEmpty()) log.info("Re-applied dlv.path=$customPath from env UserData")
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
        sessionKeyFor(env.executionId, System.identityHashCode(env))

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

/**
 * Pure session-key derivation, extracted for testability. `executionId` is unique when the
 * platform assigned it, but defaults to 0L for launches that never went through
 * `assignNewExecutionId()` (e.g. the internally-created Run-on-Target debug env). When it is
 * 0 we fall back to the env's identity hash, which is distinct for the distinct env objects
 * of concurrent runs — so two id-0 sessions never share a key.
 */
internal fun sessionKeyFor(executionId: Long, identity: Int): String =
    if (executionId != 0L) executionId.toString() else "id0:$identity"

/**
 * Resolve the `dlv.path` value a config wants asserted for its session.
 *
 *  - [DelveSource.BUNDLED] -> `""`  — empty makes the Go plugin fall back to its shipped dlv.
 *  - [DelveSource.GLOBAL]  -> the IDE [baseline] captured at startup.
 *  - [DelveSource.CUSTOM]  -> the already-expanded, already-validated custom path.
 *
 * Pure (no IDE deps) so the resolution table is unit-testable in isolation.
 */
internal fun resolveDesiredValue(
    source: DelveSource,
    baseline: String,
    expandedCustomPath: String,
): String = when (source) {
    DelveSource.BUNDLED -> ""
    DelveSource.GLOBAL -> baseline
    DelveSource.CUSTOM -> expandedCustomPath
}

/**
 * Re-apply an override at process-start through the ref-counted swapper, so it is always
 * balanced by the matching `processTerminated -> restore`. Idempotent: a no-op if this env
 * already swapped in `processStartScheduled`.
 */
internal fun reapplyOnStarting(swapper: RegistrySwapper, sessionKey: String, customPath: String?) {
    if (!customPath.isNullOrEmpty()) swapper.swap(sessionKey, customPath)
}
