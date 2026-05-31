package com.hoss.delveperconfig

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
import javax.swing.JPanel

class DelvePathSettingsEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {

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
        ButtonGroup().apply {
            add(bundledRadio)
            add(globalRadio)
            add(customRadio)
        }
        val sync = { syncEnabled() }
        bundledRadio.addItemListener { sync() }
        globalRadio.addItemListener { sync() }
        customRadio.addItemListener { sync() }
        globalRadio.isSelected = true
        syncEnabled()
    }

    private fun syncEnabled() {
        val custom = customRadio.isSelected
        pathField.isEnabled = custom
        hintLabel.isEnabled = custom
    }

    private fun selectedSource(): DelveSource = when {
        bundledRadio.isSelected -> DelveSource.BUNDLED
        customRadio.isSelected -> DelveSource.CUSTOM
        else -> DelveSource.GLOBAL
    }

    override fun resetEditorFrom(configuration: P) {
        val s = DelvePathSettingsStore.get(configuration)
        when (s.source) {
            DelveSource.BUNDLED -> bundledRadio.isSelected = true
            DelveSource.GLOBAL -> globalRadio.isSelected = true
            DelveSource.CUSTOM -> customRadio.isSelected = true
        }
        pathField.text = s.path
        syncEnabled()
    }

    override fun applyEditorTo(configuration: P) {
        val s = DelvePathSettingsStore.get(configuration)
        s.source = selectedSource()
        s.path = pathField.text.trim()
    }

    override fun createEditor(): JComponent {
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Delve binary:"))
            .addComponent(bundledRadio)
            .addComponent(globalRadio)
            .addLabeledComponent(customRadio, pathField)
            .addComponent(hintLabel)
            .panel
        panel.border = JBUI.Borders.empty(8)
        return panel
    }
}
