package com.hoss.delveswap

import com.goide.execution.GoRunConfigurationBase
import com.goide.execution.extension.GoRunConfigurationExtension
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import org.jdom.Element

/**
 * Adds a "Delve" settings tab to Go Build / Go Test (and other Go) run configurations and
 * persists the per-config override into the run configuration XML.
 *
 * Registered under the Go plugin's own extension point `com.goide.runConfigurationExtension`
 * (NOT the platform's `com.intellij.runConfigurationExtension`). The Go run-config editors only
 * render editors contributed through this Go-specific point; the base class
 * GoRunConfigurationExtension already implements the platform's abstract patchCommandLine, so we
 * only supply isApplicableFor/isEnabledFor plus the UI + persistence hooks.
 *
 * The actual dlv-binary override is NOT done here: the Go plugin launches dlv from its own
 * debugger classes (com.goide.dlv.*) reading the global `dlv.path`, not via GoExecutor's
 * build/run command line. DlvPathSwapListener performs the swap at debug-launch time.
 */
class DelvePathRunConfigurationExtension : GoRunConfigurationExtension() {

    override fun isApplicableFor(configuration: GoRunConfigurationBase<*>): Boolean = true

    override fun isEnabledFor(
        applicableConfiguration: GoRunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
    ): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override fun <P : GoRunConfigurationBase<*>?> createEditor(configuration: P): SettingsEditor<P>? =
        DelvePathSettingsEditor<GoRunConfigurationBase<*>>() as SettingsEditor<P>

    override fun getEditorTitle(): String = "Delve"

    override fun readExternal(runConfiguration: GoRunConfigurationBase<*>, element: Element) {
        runConfiguration.setDelveSettings(DelvePathSettings.readFrom(element))
    }

    override fun writeExternal(runConfiguration: GoRunConfigurationBase<*>, element: Element) {
        runConfiguration.getDelveSettings().writeTo(element)
    }

    // Frozen at the legacy id so v0.1.0 run-config XML keeps deserializing into this extension
    // after the package rename. Do not change without a migration story for existing user data.
    override fun getSerializationId(): String = "com.hoss.delveperconfig"
}
