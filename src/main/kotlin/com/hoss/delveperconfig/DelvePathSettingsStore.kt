package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.util.Key

object DelvePathSettingsStore {
    private val KEY = Key.create<DelvePathSettings>("delve-per-config.settings")

    fun get(config: RunConfigurationBase<*>): DelvePathSettings {
        var s = config.getUserData(KEY)
        if (s == null) {
            s = DelvePathSettings()
            config.putUserData(KEY, s)
        }
        return s
    }

    fun set(config: RunConfigurationBase<*>, s: DelvePathSettings) {
        config.putUserData(KEY, s)
    }
}
