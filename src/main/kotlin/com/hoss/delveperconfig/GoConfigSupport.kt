package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase

object GoConfigSupport {
    private val SUPPORTED_TYPE_IDS = setOf(
        "GoApplicationRunConfiguration",  // Go Build
        "GoTestRunConfiguration",          // Go Test
    )

    fun isSupported(config: RunConfigurationBase<*>): Boolean =
        config.type.id in SUPPORTED_TYPE_IDS
}
