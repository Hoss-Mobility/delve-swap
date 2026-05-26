package com.hoss.delveperconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SentinelRecovery : ProjectActivity {
    override suspend fun execute(project: Project) {
        val recovered = SentinelStore.readAndClear() ?: return
        thisLogger().warn("Recovered leaked dlv.path; restoring to $recovered")
        PlatformRegistryBackend().set(recovered)
    }
}
