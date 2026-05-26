package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configuration.RunConfigurationExtension
import com.intellij.openapi.options.SettingsEditor
import org.jdom.Element

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
        // RunConfigurationExtension only exposes Java-flavoured hooks (updateJavaParameters,
        // attachToProcess). Delve isn't launched via JavaParameters, and the closed Go plugin
        // doesn't invoke a generic patchCommandLine entry point on its run profile. The actual
        // override therefore lives in RegistrySwapListener, which swaps the dlv.path Registry
        // value around each launch. This extension's job is UI + persistence only.
    }
}
