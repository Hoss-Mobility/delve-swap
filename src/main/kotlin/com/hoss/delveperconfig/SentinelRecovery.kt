package com.hoss.delveperconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs once per project on startup. If the previous IDE session crashed mid-debug, the
 * sentinel file on disk holds the original `dlv.path` value that was captured before the
 * swap. We validate that value against the same rules the swap uses, then restore it.
 * Invalid sentinel values are discarded silently — a co-resident plugin or stale state
 * could in principle write garbage, and applying an unvalidated path would persistently
 * hijack `dlv.path` for all subsequent debug sessions.
 */
class SentinelRecovery : ProjectActivity {
    override suspend fun execute(project: Project) {
        val recovered = SentinelStore.readAndClear() ?: return
        val validation = DelvePathValidator.validateExpanded(recovered, overrideEnabled = true)
        if (validation.severity == DelvePathValidator.Severity.ERROR) {
            thisLogger().warn("Sentinel contained an invalid dlv.path; discarding.")
            return
        }
        thisLogger().warn("Recovered leaked dlv.path from sentinel; restoring previous value.")
        PlatformRegistryBackend().set(recovered)
    }
}
