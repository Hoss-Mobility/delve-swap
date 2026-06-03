// Hook each Go debug launch, write the chosen dlv path into the JVM system property
// `dlv.path`, restore on debug end. See KDocs on DlvPathSwapper (why a system property, not
// a Registry key) and on processStarting (Run-on-Target two-phase model).
package com.hoss.delveswap

import com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY
import com.goide.execution.GoRunConfigurationBase
import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

// Must match <notificationGroup id="..."/> in plugin.xml.
private const val NOTIFICATION_GROUP_ID = "DelveSwap"
private const val DLV_PATH_KEY = "dlv.path"

/**
 * Reads and writes the value the Go plugin observes when picking a `dlv` binary. In
 * production this is a thin wrapper over `System.getProperty("dlv.path")`; tests substitute
 * an in-memory fake.
 *
 * `dlv.path` is **not** an IntelliJ Registry key — the Go plugin never declares it as one,
 * so `Registry.get("dlv.path")` would throw on a fresh IDE. The Go plugin reads it via
 * `System.getProperty("dlv.path")` only, inside `GoRunUtil.dlv(TargetEnvironmentRequest)` at
 * debug start.
 */
internal interface DlvPathBackend {
    fun get(): String
    fun set(v: String)
}

internal class SystemPropertyBackend : DlvPathBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()

    // swap() only ever sets a validated, non-blank path, so an empty value here means
    // "restore an original that was absent" — clear the property rather than leaving an
    // empty string. The Go plugin treats empty and absent equivalently, but absent is the
    // honest signal.
    override fun set(v: String) {
        if (v.isEmpty()) System.clearProperty(DLV_PATH_KEY) else System.setProperty(DLV_PATH_KEY, v)
    }
}

/**
 * Captures the original `dlv.path` once on the first active session and restores it once the
 * last session ends. While multiple sessions are active, the backend value is the
 * most-recently-swapped value.
 *
 * Read semantics: when a debug session starts, GoRunningState.startProcess calls
 * GoRunUtil.dlv(TargetEnvironmentRequest), which reads `System.getProperty("dlv.path")` to
 * pick the dlv binary to upload. The property is read fresh on each debug start, so the
 * value present at startProcess time is what that session uses.
 *
 * Known limitation: if two debug sessions are scheduled close enough that both swap() calls
 * complete before either startProcess runs, last-writer-wins — both sessions see the second
 * swap's value. A per-session bridge is tracked as a follow-up plan.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered on
 * next IDE startup.
 */
internal class DlvPathSwapper(
    private val backend: DlvPathBackend,
    private val sentinel: SentinelStore,
) {
    private var original: String? = null
    private val active = mutableSetOf<String>()
    private val lock = Any()

    /**
     * What `dlv.path` would be with no swaps active — the value the GLOBAL tier resolves to.
     * Re-read from the backend each call so a user who edits the IDE-wide `dlv.path` between
     * debug sessions sees their new value, not a snapshot from IDE-startup time. During an
     * active swap the backend holds a swapped value, so we return the captured pre-swap
     * original instead.
     */
    val baseline: String
        get() = synchronized(lock) { original ?: backend.get() }

    fun swap(sessionId: String, newValue: String) {
        synchronized(lock) {
            if (sessionId in active) return  // duplicate dispatch — no-op
            if (active.isEmpty()) {
                val captured = backend.get()
                original = captured
                sentinel.write(captured)
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
                sentinel.clear()
            }
        }
    }

    fun hasActiveSessions(): Boolean = synchronized(lock) { active.isNotEmpty() }
}

/**
 * Persists the pre-swap `dlv.path` to disk so a mid-debug IDE crash can be recovered on next
 * start. Constructor takes the target file so tests can pass a `@TempDir` path; production
 * uses [default], which depends on the IDE's `PathManager` and is therefore not safe to call
 * outside an IDE context.
 */
internal class SentinelStore(private val file: Path) {
    fun write(original: String) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, original)
        } catch (_: Exception) { /* best effort */ }
    }

    fun clear() {
        try { Files.deleteIfExists(file) } catch (_: Exception) {}
    }

    fun readAndClear(): String? = try {
        if (!Files.exists(file)) null
        else Files.readString(file).also { Files.deleteIfExists(file) }
    } catch (_: Exception) { null }

    companion object {
        fun default(): SentinelStore =
            SentinelStore(Path.of(PathManager.getSystemPath(), "delveswap", "sentinel"))
    }
}

@Service(Service.Level.APP)
class GlobalDlvPathSwapper {
    internal val swapper = DlvPathSwapper(SystemPropertyBackend(), SentinelStore.default())
}

class DlvPathSwapListener(private val project: Project) : ExecutionListener {

    private val log = thisLogger()
    private val swapper: DlvPathSwapper
        get() = service<GlobalDlvPathSwapper>().swapper

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        // Go Remote attaches to an already-running dlv and is not a GoRunConfigurationBase,
        // so it is naturally excluded.
        val profile = env.runProfile as? GoRunConfigurationBase<*> ?: return

        // Every supported config asserts its resolved value — including GLOBAL and BUNDLED.
        // The old early-return left dlv.path untouched, so a non-custom config inherited
        // whatever a concurrent session had swapped in (the reported cross-project leak).
        val settings = profile.getDelveSettings()
        val expandedPath = if (settings.source == DelveSource.CUSTOM)
            PathExpansion.expand(settings.path, project, collectEnv(profile))
        else ""
        val resolution = resolveForLaunch(settings, swapper.baseline, expandedPath)

        resolution.warning?.let { notify(it.title, it.content) }
        log.info("Asserting dlv.path -> '${resolution.value}' for executor=$executorId (source=${settings.source})")
        swapper.swap(sessionKey(env), resolution.value)
        // Carry a non-empty value onto the second env of a Run-on-Target debug, which reads
        // CUSTOM_DELVE_PATH_KEY rather than the system property. An empty value (BUNDLED, or
        // GLOBAL with no IDE-wide dlv.path) means "no override", so we leave the key unset.
        if (resolution.value.isNotEmpty()) {
            env.putUserData(CUSTOM_DELVE_PATH_KEY, resolution.value)
        }
    }

    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        // A "Run on target" debug fires two ExecutionEnvironments back-to-back: a fast build
        // sub-execution that terminates in ~200ms, then the real debug. The build run's
        // processTerminated restores dlv.path before the real debug reads it. The Go plugin
        // copies our CUSTOM_DELVE_PATH_KEY onto the second env, so we re-apply the override
        // here — through the ref-counted swapper, so it stays balanced by *this* env's
        // processTerminated -> restore and never leaks past the session. The swap is
        // idempotent: if this env already swapped in processStartScheduled, it's a no-op.
        val customPath = env.getUserData(CUSTOM_DELVE_PATH_KEY)
        if (!customPath.isNullOrEmpty()) {
            swapper.swap(sessionKey(env), customPath)
            // IDE logs are user-owned, so we log the resolved path verbatim for diagnosis.
            log.info("Re-applied dlv.path=$customPath from env UserData")
        }
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

    // Reflection rather than a direct cast because `getCustomEnvironment` is a convention
    // exposed by several GoRunConfigurationBase subclasses (GoApplication-/GoTest-/GoBuild-
    // Configuration) but is not declared on the base type, and the exact subtype that owns
    // it has shifted across Go-plugin releases. Silent degradation matches the desired UX:
    // a config without custom env just falls back to the process env.
    private fun collectEnv(profile: RunConfigurationBase<*>): Map<String, String> {
        val env = HashMap<String, String>(System.getenv())
        runCatching { profile::class.java.getMethod("getCustomEnvironment").invoke(profile) as? Map<*, *> }
            .getOrNull()
            ?.forEach { (k, v) -> if (k is String && v is String) env[k] = v }
        return env
    }

    private fun notify(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }
}

/**
 * `executionId` is unique when the platform assigned it, but defaults to 0L for launches
 * that never went through `assignNewExecutionId()` (e.g. the internally-created
 * Run-on-Target debug env). When it is 0 we fall back to the env's identity hash, which is
 * distinct for the distinct env objects of concurrent runs — so two id-0 sessions never
 * share a key.
 */
internal fun sessionKeyFor(executionId: Long, identity: Int): String =
    if (executionId != 0L) executionId.toString() else "id0:$identity"

/** A user-visible warning the listener fires after applying a [Resolution]. */
internal data class Notice(val title: String, val content: String)

/** The value to assert plus an optional warning. */
internal data class Resolution(val value: String, val warning: Notice? = null)

/**
 * Map settings → asserted `dlv.path` value, with validation for CUSTOM paths and the
 * "fall back to baseline on ERROR" rule. Pure: caller pre-expands macros + env vars so this
 * function has no IDE deps and is unit-testable in isolation.
 *
 * The ERROR-branch fallback to [baseline] (rather than "leave dlv.path untouched") is what
 * keeps the fallback itself leak-proof.
 */
internal fun resolveForLaunch(
    settings: DelvePathSettings,
    baseline: String,
    expandedPath: String,
): Resolution = when (settings.source) {
    DelveSource.BUNDLED -> Resolution("")
    DelveSource.GLOBAL -> Resolution(baseline)
    DelveSource.CUSTOM -> when (val v = DelvePathValidator.validate(expandedPath)) {
        DelvePathValidator.Result.OK -> Resolution(expandedPath)
        DelvePathValidator.Result.WARN_NOT_EXECUTABLE -> Resolution(
            value = expandedPath,
            warning = Notice("Delve path warning", "${v.message}: $expandedPath. Attempting launch anyway."),
        )
        DelvePathValidator.Result.ERROR_EMPTY,
        DelvePathValidator.Result.ERROR_NOT_FOUND -> Resolution(
            value = baseline,
            warning = Notice("Delve path override skipped", "${v.message}: $expandedPath. Falling back to global dlv.path."),
        )
    }
}
