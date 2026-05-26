package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase

object GoConfigSupport {
    // Type IDs come from the closed-source JetBrains Go plugin
    // (`GoApplicationRunConfigurationType.getId()` and the test equivalent).
    // They are stable across Go plugin releases in 261.* but should be
    // re-verified on each major GoLand/Go-plugin upgrade — the Milestone 0
    // probe in plan section 5 captures the current values at runtime.
    private val SUPPORTED_TYPE_IDS = setOf(
        "GoApplicationRunConfiguration",  // Go Build
        "GoTestRunConfiguration",          // Go Test
    )

    fun isSupported(config: RunConfigurationBase<*>): Boolean =
        config.type.id in SUPPORTED_TYPE_IDS
}
