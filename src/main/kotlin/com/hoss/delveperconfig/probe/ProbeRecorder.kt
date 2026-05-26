package com.hoss.delveperconfig.probe

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configuration.RunConfigurationExtension
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import org.jdom.Element

private val LOG_FILE: Path =
    Path.of(System.getProperty("user.home"), ".cache", "delve-plugin-probe", "probe.log")

private fun record(message: String) {
    try {
        Files.createDirectories(LOG_FILE.parent)
        Files.writeString(
            LOG_FILE,
            "${Instant.now()} pid=${ProcessHandle.current().pid()} $message\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    } catch (_: Throwable) { /* probe must never throw */ }
}

class ProbeExtension : RunConfigurationExtension() {
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = configuration.type.id in setOf(
            "GoApplicationRunConfiguration", "GoTestRunConfiguration",
        )
        record("isApplicableFor type=${configuration.type.id} class=${configuration::class.java.name} -> $applicable")
        return applicable
    }

    override fun <P : RunConfigurationBase<*>?> createEditor(configuration: P): SettingsEditor<P>? {
        record("createEditor type=${configuration?.type?.id} class=${configuration?.let { it::class.java.name }}")
        return null
    }

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        record("readExternal type=${runConfiguration.type.id}")
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        record("writeExternal type=${runConfiguration.type.id}")
    }

    override fun <T : RunConfigurationBase<*>?> updateJavaParameters(
        configuration: T & Any,
        params: com.intellij.execution.configurations.JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        record("updateJavaParameters type=${configuration.type.id}")
    }

    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?,
    ) {
        record("attachToProcess type=${configuration.type.id} handlerClass=${handler::class.java.name}")
    }
}

class ProbeExecutionListener(private val project: Project) : ExecutionListener {
    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        record("processStartScheduled executor=$executorId profile=${env.runProfile::class.java.name}")
    }
    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        record("processStarting executor=$executorId profile=${env.runProfile::class.java.name}")
    }
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        record("processStarted executor=$executorId handlerClass=${handler::class.java.name}")
    }
    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        record("processTerminating executor=$executorId")
    }
    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        record("processTerminated executor=$executorId exitCode=$exitCode")
    }
}
