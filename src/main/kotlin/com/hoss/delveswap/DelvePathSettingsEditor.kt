package com.hoss.delveswap

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * The "Delve" tab on Go run configurations: three radios mirroring [DelveSource], plus a
 * path field (macro/env-var capable) that is only live while CUSTOM is selected.
 */
internal class DelvePathSettingsEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {

    private val bundledRadio = JBRadioButton("GoLand bundled delve")
    private val globalRadio = JBRadioButton("Global dlv.path")
    private val customRadio = JBRadioButton("Custom:")

    private val pathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select Delve Binary")
                .withDescription("Path to a dlv (Delve) executable"),
        )
    }
    private val hintLabel = JBLabel("Supports \$PROJECT_DIR\$, \$USER_HOME\$, and \$VAR / \${VAR} env vars.")

    init {
        val group = ButtonGroup()
        listOf(bundledRadio, globalRadio, customRadio).forEach {
            group.add(it)
            it.addItemListener { syncEnabled() }
        }
        globalRadio.isSelected = true
        syncEnabled()
    }

    // Greying the path field when Custom isn't selected tells the user it would be ignored.
    private fun syncEnabled() {
        val custom = customRadio.isSelected
        pathField.isEnabled = custom
        hintLabel.isEnabled = custom
    }

    override fun resetEditorFrom(configuration: P) {
        val s = configuration.getDelveSettings()
        when (s.source) {
            DelveSource.BUNDLED -> bundledRadio.isSelected = true
            DelveSource.GLOBAL -> globalRadio.isSelected = true
            DelveSource.CUSTOM -> customRadio.isSelected = true
        }
        pathField.text = s.path
        syncEnabled()
    }

    override fun applyEditorTo(configuration: P) {
        val source = when {
            bundledRadio.isSelected -> DelveSource.BUNDLED
            customRadio.isSelected -> DelveSource.CUSTOM
            else -> DelveSource.GLOBAL
        }
        configuration.setDelveSettings(DelvePathSettings(source = source, path = pathField.text.trim()))
    }

    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("Delve binary:"))
        .addComponent(bundledRadio)
        .addComponent(globalRadio)
        .addLabeledComponent(customRadio, pathField)
        .addComponent(hintLabel)
        .panel
        .apply { border = JBUI.Borders.empty(8) }
}
