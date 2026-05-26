package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configuration.RunConfigurationExtension
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import org.jdom.Element

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

class DelvePathRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        GoConfigSupport.isSupported(configuration)

    @Suppress("UNCHECKED_CAST")
    override fun <P : RunConfigurationBase<*>?> createEditor(configuration: P): SettingsEditor<P>? {
        if (configuration == null || !GoConfigSupport.isSupported(configuration)) return null
        return DelvePathSettingsEditor<RunConfigurationBase<*>>() as SettingsEditor<P>
    }

    override fun getEditorTitle(): String = "Delve"

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val s = DelvePathSettings().apply { readFrom(element) }
        DelvePathSettingsStore.set(runConfiguration, s)
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        DelvePathSettingsStore.get(runConfiguration).writeTo(element)
    }

    override fun validateConfiguration(
        configuration: RunConfigurationBase<*>,
        isExecution: Boolean,
    ) {
        // Soft check — full validation happens at launch where we know the process env.
    }

    override fun <T : RunConfigurationBase<*>?> updateJavaParameters(
        configuration: T & Any,
        params: com.intellij.execution.configurations.JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        // No-op: Delve is not Java. Override mechanism lives in RegistrySwapListener.
    }
}
