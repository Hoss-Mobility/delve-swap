package com.hoss.delveperconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

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
