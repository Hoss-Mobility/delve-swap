// Crash recovery: on the next IDE start after a mid-debug crash, read the sentinel file
// (written by DlvPathSwapper before each swap) and restore the original dlv.path so the
// user's IDE-wide setting isn't permanently overwritten.
package com.hoss.delveswap

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/** What to do with the value read from the sentinel file. Pure, so it is unit-testable. */
internal sealed interface RecoveryAction {
    data object None : RecoveryAction          // no sentinel -> nothing to recover
    data object Clear : RecoveryAction          // original was absent -> clear dlv.path
    data class Restore(val value: String) : RecoveryAction
    data object Discard : RecoveryAction         // invalid/garbage sentinel -> leave dlv.path
}

// Blank sentinel means "original was unset" → Clear (not ERROR_EMPTY). The validator would
// wrongly classify "" as ERROR; we short-circuit it.
internal fun recoveryAction(recovered: String?): RecoveryAction = when {
    recovered == null -> RecoveryAction.None
    recovered.isBlank() -> RecoveryAction.Clear
    DelvePathValidator.validate(recovered).severity == DelvePathValidator.Severity.ERROR ->
        RecoveryAction.Discard
    else -> RecoveryAction.Restore(recovered)
}

/**
 * If the previous IDE session crashed mid-debug, the sentinel file holds the original
 * `dlv.path` captured before the swap; we validate and restore it on next start.
 *
 * Two guards prevent this per-project activity from corrupting a *running* session:
 *  - run-once per IDE process ([firstRun]) — the EP fires once per opened project, but
 *    crash recovery is a per-process startup concern;
 *  - skip while any swap is active (`hasActiveSessions`) — a live session legitimately owns
 *    `dlv.path` and writes its own sentinel, which its own `restore()` clears. The sentinel
 *    we'd see then is not a crash artifact.
 *
 * A blank sentinel means the original was absent, so we clear rather than run it through the
 * path validator (which would report ERROR_EMPTY and wrongly discard).
 */
class SentinelRecovery : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!firstRun.compareAndSet(false, true)) return
        if (service<GlobalDlvPathSwapper>().swapper.hasActiveSessions()) return
        val log = thisLogger()
        val backend = SystemPropertyBackend()
        val sentinel = SentinelStore.default()
        when (val action = recoveryAction(sentinel.readAndClear())) {
            RecoveryAction.None -> Unit
            RecoveryAction.Clear -> backend.set("")
            RecoveryAction.Discard -> log.warn("Sentinel contained an invalid dlv.path; discarding.")
            is RecoveryAction.Restore -> {
                log.warn("Recovered leaked dlv.path from sentinel; restoring previous value.")
                backend.set(action.value)
            }
        }
    }

    private companion object {
        val firstRun = AtomicBoolean(false)
    }
}
