package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class DelvePathSettingsEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {

    private val useGlobalCheckbox = JBCheckBox("Use global dlv.path", true)
    private val pathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select Delve Binary",
            "Path to a dlv (Delve) executable",
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
        )
    }
    private val hintLabel = JBLabel("Supports \$PROJECT_DIR\$, \$USER_HOME\$, and \$VAR / \${VAR} env vars.")

    init {
        useGlobalCheckbox.addItemListener { syncEnabled() }
        syncEnabled()
    }

    private fun syncEnabled() {
        pathField.isEnabled = !useGlobalCheckbox.isSelected
        hintLabel.isEnabled = !useGlobalCheckbox.isSelected
    }

    override fun resetEditorFrom(configuration: P) {
        val s = DelvePathSettingsStore.get(configuration)
        useGlobalCheckbox.isSelected = s.useGlobal
        pathField.text = s.path
        syncEnabled()
    }

    override fun applyEditorTo(configuration: P) {
        val s = DelvePathSettingsStore.get(configuration)
        s.useGlobal = useGlobalCheckbox.isSelected
        s.path = pathField.text
    }

    override fun createEditor(): JComponent {
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addComponent(useGlobalCheckbox)
            .addLabeledComponent("Delve binary:", pathField)
            .addComponent(hintLabel)
            .panel
        panel.border = JBUI.Borders.empty(8)
        return panel
    }
}
