package com.hoss.delveperconfig

import com.goide.execution.GoRunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationBase

object GoConfigSupport {
    // Detect Go local run configurations (Go Build, Go Test, Go Application — all of which
    // extend GoRunConfigurationBase and launch dlv from the global dlv.path). Using the class
    // hierarchy rather than guessed type-id strings makes this robust across Go-plugin releases.
    // Go Remote attaches to an already-running dlv and is not a GoRunConfigurationBase, so it
    // is naturally excluded.
    fun isSupported(config: RunConfigurationBase<*>): Boolean =
        config is GoRunConfigurationBase<*>
}
