package com.hoss.delveperconfig

import com.intellij.execution.configuration.RunConfigurationExtensionBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import org.jdom.Element

class DelvePathRunConfigurationExtension : RunConfigurationExtensionBase<RunConfigurationBase<*>>() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        GoConfigSupport.isSupported(configuration)

    override fun isEnabledFor(
        applicableConfiguration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
    ): Boolean = true

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

    override fun getSerializationId(): String = "com.hoss.delveperconfig"

    override fun patchCommandLine(
        configuration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        // No-op. We considered patching the dlv path here, but in 2026.1 the Go plugin
        // builds its dlv command line via internal classes that don't route through
        // RunConfigurationExtensionBase.patchCommandLine for the debug launch. The actual
        // override therefore lives in RegistrySwapListener, which swaps the dlv.path
        // Registry value around each Go debug session. This extension provides the UI
        // and persists per-config settings; the listener does the runtime override.
    }
}
