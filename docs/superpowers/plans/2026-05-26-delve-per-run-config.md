# Per-Run-Configuration Delve Path Plugin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a JetBrains Marketplace plugin that lets a user override the Delve (`dlv`) debugger binary path per Go run/debug configuration, falling back to the global `dlv.path` when unset.

**Architecture:** Two-half plugin. Half one — a `RunConfigurationExtension` adds settings + UI to the Go Build and Go Test run configurations and persists the override into the run config XML. Half two — an `ExecutionListener` swaps `Registry.get("dlv.path")` immediately before each Go debug launch and restores it on process termination, depending only on the public Registry key rather than the closed Go plugin's internals. The extension's own `patchCommandLine` is wired up best-effort in case JetBrains' Go plugin honors it, but the Registry swap is the load-bearing mechanism.

**Tech Stack:** Kotlin · JVM target 21 · IntelliJ Platform Gradle Plugin 2.x · GoLand 2026.1+ (`since-build = 261.*`) · JUnit 5 / `BasePlatformTestCase` for tests · GitHub Actions for CI + signing.

**Reference spec:** `docs/superpowers/specs/2026-05-26-delve-per-run-config-design.md`

---

## File Map

Source:
- `build.gradle.kts` — Gradle build config.
- `settings.gradle.kts` — single-module settings.
- `gradle.properties` — versions.
- `src/main/resources/META-INF/plugin.xml` — plugin manifest.
- `src/main/kotlin/com/hoss/delveperconfig/GoConfigSupport.kt` — predicate: is this Run Configuration a Go Build or Go Test?
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt` — per-config state class with XML round-trip.
- `src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt` — macro + env-var expansion.
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathValidator.kt` — file-exists / executable / `dlv version` checks.
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt` — Swing settings panel.
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathRunConfigurationExtension.kt` — the `RunConfigurationExtension` glue (UI + read/writeExternal + best-effort patchCommandLine).
- `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` — `ExecutionListener` that swaps `dlv.path` around launches with a project-scoped lock.
- `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt` — startup activity that restores a leaked Registry value if the IDE crashed mid-session.
- `src/main/kotlin/com/hoss/delveperconfig/probe/ProbeRecorder.kt` — temporary instrumentation, deleted after Milestone 0.

Tests:
- `src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt`
- `src/test/kotlin/com/hoss/delveperconfig/PathExpansionTest.kt`
- `src/test/kotlin/com/hoss/delveperconfig/DelvePathValidatorTest.kt`
- `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

Docs:
- `README.md` — user-facing.
- `docs/superpowers/manual-smoke-matrix.md` — manual test plan.

---

## Task 1: Initialize Gradle project + scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "delve-per-config"
```

- [ ] **Step 2: Create `gradle.properties`**

```properties
pluginGroup = com.hoss
pluginName = delve-per-config
pluginVersion = 0.1.0
pluginSinceBuild = 261
platformType = GO
platformVersion = 2026.1
kotlin.stdlib.default.dependency = false
org.gradle.jvmargs = -Xmx2g
org.gradle.parallel = true
```

- [ ] **Step 3: Create `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get(),
        )
        bundledPlugin("org.jetbrains.plugins.go")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Create `.gitignore`**

```
.gradle/
build/
.idea/
*.iml
out/
local.properties
```

- [ ] **Step 5: Verify the build resolves**

Run: `gradle --no-daemon help`
Expected: BUILD SUCCESSFUL. (No source files yet; this confirms the Gradle config parses.)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore
git commit -m "chore: initialize Gradle project (IntelliJ Platform 2.x, GoLand 2026.1+)"
```

---

## Task 2: plugin.xml skeleton

**Files:**
- Create: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the manifest**

```xml
<idea-plugin>
    <id>com.hoss.delve-per-config</id>
    <name>Per-Config Delve Debugger Path</name>
    <vendor email="stephan.kast@hoss-mobility.com" url="https://hoss-mobility.com">Hoss Mobility</vendor>

    <description><![CDATA[
        Override the path to the Delve (dlv) debugger binary on a per-run-configuration
        basis for Go Build and Go Test configurations. Falls back to the global
        <code>dlv.path</code> setting when no override is set. See
        <a href="https://youtrack.jetbrains.com/issue/GO-19800">YouTrack GO-19800</a>
        for background.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.plugins.go</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- registered in later tasks -->
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Verify the plugin builds**

Run: `gradle --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL; a zip appears under `build/distributions/`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "chore: plugin.xml skeleton"
```

---

## Task 3: Go-config detection helper (TDD)

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/GoConfigSupport.kt`

This helper is the single place that decides "is this a Go Build or Go Test run config?" — used by both the extension's `isApplicableFor` and the `ExecutionListener`.

- [ ] **Step 1: Write the helper**

Without access to the Go plugin's class names yet (Milestone 0 confirms them), use the run configuration's `type.id` string, which is stable public API.

```kotlin
package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase

object GoConfigSupport {
    private val SUPPORTED_TYPE_IDS = setOf(
        "GoApplicationRunConfiguration",  // Go Build
        "GoTestRunConfiguration",          // Go Test
    )

    fun isSupported(config: RunConfigurationBase<*>): Boolean =
        config.type.id in SUPPORTED_TYPE_IDS
}
```

- [ ] **Step 2: Commit (no test — pure delegation; verified by integration in later tasks)**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/GoConfigSupport.kt
git commit -m "feat: predicate for supported Go run configuration types"
```

Note: if Milestone 0 reveals the actual type IDs differ from these guesses, update this file then.

---

## Task 4: Milestone 0 probe — instrumented extension

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/probe/ProbeRecorder.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

The probe is shipped, run manually against GoLand, and **deleted** before v1. Its log answers two questions: does `createEditor` fire, and does `patchCommandLine` fire before the Go plugin reads `dlv.path`?

- [ ] **Step 1: Create the probe recorder**

```kotlin
package com.hoss.delveperconfig.probe

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configuration.RunConfigurationExtension
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.execution.util.JavaParametersUtil
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
```

- [ ] **Step 2: Register the probe in `plugin.xml`**

Replace the empty `<extensions>` block:

```xml
<extensions defaultExtensionNs="com.intellij">
    <runConfigurationExtension implementation="com.hoss.delveperconfig.probe.ProbeExtension"/>
</extensions>

<projectListeners>
    <listener class="com.hoss.delveperconfig.probe.ProbeExecutionListener"
              topic="com.intellij.execution.ExecutionListener"/>
</projectListeners>
```

- [ ] **Step 3: Build a sandbox IDE and run the probe**

Run: `gradle --no-daemon runIde`
Expected: A sandbox GoLand instance starts. Open any Go project, create a Go Build run configuration, run it once with the **Run** executor, then with the **Debug** executor. Repeat with Go Test.

- [ ] **Step 4: Read the probe output**

Run: `cat ~/.cache/delve-plugin-probe/probe.log`
Expected: lines for each lifecycle event. Capture the output into a checkpoint note.

- [ ] **Step 5: Record findings**

Add `docs/superpowers/probe-findings.md` with the captured log excerpts and the answers to:

1. Did `createEditor` fire? (yes/no)
2. Did `patchCommandLine` / `updateJavaParameters` / `attachToProcess` fire on debug launch? (yes/no, in what order)
3. What concrete class is `runConfiguration` for Go Build and Go Test? (use to refine `GoConfigSupport` if needed)

- [ ] **Step 6: Commit findings**

```bash
git add docs/superpowers/probe-findings.md src/main/kotlin/com/hoss/delveperconfig/probe src/main/resources/META-INF/plugin.xml
git commit -m "feat(probe): instrument Go run config extension hooks + record Milestone 0 findings"
```

- [ ] **Step 7: Checkpoint — review with user**

Before proceeding to Task 5, present the probe findings to the user and confirm Path A (extension hooks) is viable for UI. If `createEditor` did NOT fire, return to design phase to pick an alternate UI strategy (editor wrapping, custom ConfigurationType, or settings-page sidecar). Do not proceed past this checkpoint silently.

---

## Task 5: DelvePathSettings persistent state (TDD)

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt`
- Test: `src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hoss.delveperconfig

import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DelvePathSettingsTest {
    @Test
    fun `default settings use global path and have empty path string`() {
        val s = DelvePathSettings()
        assertTrue(s.useGlobal)
        assertEquals("", s.path)
    }

    @Test
    fun `write then read round-trips values`() {
        val s = DelvePathSettings(useGlobal = false, path = "\$PROJECT_DIR\$/dlv")
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertFalse(loaded.useGlobal)
        assertEquals("\$PROJECT_DIR\$/dlv", loaded.path)
    }

    @Test
    fun `absent element decodes to defaults`() {
        val parent = Element("configuration")
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertTrue(loaded.useGlobal)
        assertEquals("", loaded.path)
    }

    @Test
    fun `writeTo with default values writes no element`() {
        val parent = Element("configuration")
        DelvePathSettings().writeTo(parent)
        assertTrue(parent.children.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.DelvePathSettingsTest`
Expected: FAIL with `DelvePathSettings` unresolved.

- [ ] **Step 3: Implement DelvePathSettings**

```kotlin
package com.hoss.delveperconfig

import org.jdom.Element

class DelvePathSettings(
    var useGlobal: Boolean = true,
    var path: String = "",
) {
    fun writeTo(parent: Element) {
        if (useGlobal && path.isEmpty()) return  // default state — no element
        val el = Element(ELEMENT_NAME).apply {
            setAttribute(ATTR_USE_GLOBAL, useGlobal.toString())
            setAttribute(ATTR_PATH, path)
        }
        parent.addContent(el)
    }

    fun readFrom(parent: Element) {
        val el = parent.getChild(ELEMENT_NAME) ?: return
        useGlobal = el.getAttributeValue(ATTR_USE_GLOBAL)?.toBoolean() ?: true
        path = el.getAttributeValue(ATTR_PATH) ?: ""
    }

    fun copy(): DelvePathSettings = DelvePathSettings(useGlobal, path)

    companion object {
        const val ELEMENT_NAME = "delvePathSettings"
        const val ATTR_USE_GLOBAL = "useGlobal"
        const val ATTR_PATH = "path"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.DelvePathSettingsTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt
git commit -m "feat: DelvePathSettings persistent state with XML round-trip"
```

---

## Task 6: Path expansion (TDD)

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt`
- Test: `src/test/kotlin/com/hoss/delveperconfig/PathExpansionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathExpansionTest {
    @Test
    fun `unbraced env var is expanded`() {
        val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("\$ARM_DLV/bin", env))
    }

    @Test
    fun `braced env var is expanded`() {
        val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("\${ARM_DLV}/bin", env))
    }

    @Test
    fun `missing env var is left literally`() {
        val env = emptyMap<String, String>()
        assertEquals("\$MISSING/x", PathExpansion.expandEnv("\$MISSING/x", env))
    }

    @Test
    fun `multiple vars in one string`() {
        val env = mapOf("A" to "alpha", "B" to "beta")
        assertEquals("alpha/beta", PathExpansion.expandEnv("\$A/\${B}", env))
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals("", PathExpansion.expandEnv("", emptyMap()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.PathExpansionTest`
Expected: FAIL with `PathExpansion` unresolved.

- [ ] **Step 3: Implement PathExpansion**

```kotlin
package com.hoss.delveperconfig

import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project

object PathExpansion {
    private val ENV_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    /** Pure function — expands $VAR and ${VAR} against the provided map. Used by tests and runtime. */
    fun expandEnv(input: String, env: Map<String, String>): String =
        ENV_PATTERN.replace(input) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            env[name] ?: match.value
        }

    /** Full runtime expansion: IntelliJ path macros first, then env vars from the supplied map. */
    fun expand(input: String, project: Project, env: Map<String, String>): String {
        if (input.isEmpty()) return input
        val macroExpanded = PathMacroManager.getInstance(project).expandPath(input)
        return expandEnv(macroExpanded, env)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.PathExpansionTest`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt src/test/kotlin/com/hoss/delveperconfig/PathExpansionTest.kt
git commit -m "feat: path expansion for IntelliJ macros + env vars"
```

---

## Task 7: Path validator (TDD)

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/DelvePathValidator.kt`
- Test: `src/test/kotlin/com/hoss/delveperconfig/DelvePathValidatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DelvePathValidatorTest {

    @Test
    fun `empty path with override enabled is an error`() {
        val r = DelvePathValidator.validateExpanded(expandedPath = "", overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.ERROR_EMPTY, r)
    }

    @Test
    fun `empty path with override disabled is ok`() {
        val r = DelvePathValidator.validateExpanded(expandedPath = "", overrideEnabled = false)
        assertEquals(DelvePathValidator.Result.OK_USE_GLOBAL, r)
    }

    @Test
    fun `nonexistent file is an error`(@TempDir tmp: Path) {
        val missing = tmp.resolve("nope").toString()
        val r = DelvePathValidator.validateExpanded(missing, overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.ERROR_NOT_FOUND, r)
    }

    @Test
    fun `non-executable file is a warning`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv-noexec"))
        val r = DelvePathValidator.validateExpanded(f.toString(), overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.WARN_NOT_EXECUTABLE, r)
    }

    @Test
    fun `executable file is ok`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv"))
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
        val r = DelvePathValidator.validateExpanded(f.toString(), overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.OK, r)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.DelvePathValidatorTest`
Expected: FAIL with `DelvePathValidator` unresolved.

- [ ] **Step 3: Implement DelvePathValidator**

```kotlin
package com.hoss.delveperconfig

import java.nio.file.Files
import java.nio.file.Path

object DelvePathValidator {

    enum class Result(val message: String, val severity: Severity) {
        OK("OK", Severity.OK),
        OK_USE_GLOBAL("Will use global dlv.path", Severity.OK),
        ERROR_EMPTY("Delve path is empty", Severity.ERROR),
        ERROR_NOT_FOUND("File does not exist", Severity.ERROR),
        WARN_NOT_EXECUTABLE("File is not executable", Severity.WARN),
    }

    enum class Severity { OK, WARN, ERROR }

    fun validateExpanded(expandedPath: String, overrideEnabled: Boolean): Result {
        if (!overrideEnabled) return Result.OK_USE_GLOBAL
        if (expandedPath.isBlank()) return Result.ERROR_EMPTY
        val p: Path = Path.of(expandedPath)
        if (!Files.exists(p)) return Result.ERROR_NOT_FOUND
        if (!Files.isExecutable(p)) return Result.WARN_NOT_EXECUTABLE
        return Result.OK
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.DelvePathValidatorTest`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathValidator.kt src/test/kotlin/com/hoss/delveperconfig/DelvePathValidatorTest.kt
git commit -m "feat: validator for expanded Delve binary path"
```

---

## Task 8: Settings editor UI

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt`

This is a Swing panel rendered as a section inside the Go run configuration editor. Tested manually in Task 11 since Swing UI tests in light platform are heavy and not load-bearing here.

- [ ] **Step 1: Implement the editor**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt
git commit -m "feat: settings editor UI for per-config Delve path"
```

Note: the `DelvePathSettingsStore` accessor is introduced in the next task — this file won't compile until Task 9 lands. That's intentional; commit after Task 9 if you prefer atomic commits, but this plan keeps the editor change separate for review readability.

---

## Task 9: RunConfigurationExtension (UI binding + XML persistence + storage helper)

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/DelvePathRunConfigurationExtension.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

This is the glue. It connects: persistence (via DelvePathSettings), runtime access (via a UserData key stored on the run config), and the UI (via DelvePathSettingsEditor). It also attempts best-effort `patchCommandLine` for any future case where the Go plugin honors it.

- [ ] **Step 1: Implement the extension + storage helper**

```kotlin
package com.hoss.delveperconfig

import com.intellij.execution.configurations.GeneralCommandLine
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
        val s = DelvePathSettingsStore.get(configuration)
        if (s.useGlobal || s.path.isBlank()) return
        // Soft check — we don't expand macros here because we don't have process env yet.
        // Full validation happens at launch.
    }

    override fun <T : RunConfigurationBase<*>?> updateJavaParameters(
        configuration: T & Any,
        params: com.intellij.execution.configurations.JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        // No-op: Delve is not Java. We override the path via Registry swap (see RegistrySwapListener).
        // Kept overridden because some extension contracts require all hooks be present.
    }
}
```

- [ ] **Step 2: Replace the probe registration in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <runConfigurationExtension implementation="com.hoss.delveperconfig.DelvePathRunConfigurationExtension"/>
</extensions>
```

Delete the `<projectListeners>` block from Task 4 (the probe listener) — it gets replaced in Task 10.

- [ ] **Step 3: Delete the probe code**

```bash
rm -r src/main/kotlin/com/hoss/delveperconfig/probe
```

- [ ] **Step 4: Verify everything builds and tests still pass**

Run: `gradle --no-daemon test buildPlugin`
Expected: BUILD SUCCESSFUL. Existing unit tests for `DelvePathSettings`, `PathExpansion`, `DelvePathValidator` pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathRunConfigurationExtension.kt src/main/resources/META-INF/plugin.xml
git rm -r src/main/kotlin/com/hoss/delveperconfig/probe
git commit -m "feat: RunConfigurationExtension wires UI + persistence; remove Milestone 0 probe"
```

---

## Task 10: RegistrySwapListener — the load-bearing override mechanism

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Write the unit test (pure logic — locking + swap semantics)**

```kotlin
package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegistrySwapListenerTest {

    @Test
    fun `swap-restore on a single execution returns to original value`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)

        val sessionId = "exec-1"
        swapper.swap(sessionId, "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm", backend.get())

        swapper.restore(sessionId)
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `overlapping sessions both see their swap; original restored only after last release`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)

        swapper.swap("a", "/opt/dlv-a")
        assertEquals("/opt/dlv-a", backend.get())   // session A's launch sees /opt/dlv-a
        swapper.swap("b", "/opt/dlv-b")
        assertEquals("/opt/dlv-b", backend.get())   // session B's launch sees /opt/dlv-b

        // While both sessions are active, the backend value is intentionally undefined w.r.t.
        // any one session — the dlv read happens during swap(), not later. The only invariant
        // is that the original is restored once all sessions end.

        swapper.restore("a")
        assertEquals("/opt/dlv-b", backend.get())   // B still active; backend not restored yet

        swapper.restore("b")
        assertEquals("/usr/bin/dlv", backend.get()) // last release restores the original
    }

    @Test
    fun `restore is idempotent and safe for unknown session ids`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)
        swapper.restore("never-swapped")  // must not throw
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `concurrent swaps from two threads do not corrupt the original value`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        repeat(50) { i ->
            pool.submit {
                start.await()
                swapper.swap("s$i", "/opt/dlv-$i")
                swapper.restore("s$i")
            }
        }
        start.countDown()
        pool.shutdown()
        check(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("/usr/bin/dlv", backend.get())
    }

    private class TestRegistryBackend(initial: String) : RegistryBackend {
        @Volatile private var value: String = initial
        override fun get(): String = value
        override fun set(v: String) { value = v }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.RegistrySwapListenerTest`
Expected: FAIL with `RegistrySwapper`, `RegistryBackend` unresolved.

- [ ] **Step 3: Implement the swapper, backend, and listener**

```kotlin
package com.hoss.delveperconfig

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Files
import java.nio.file.Path

interface RegistryBackend {
    fun get(): String
    fun set(v: String)
}

class PlatformRegistryBackend : RegistryBackend {
    override fun get(): String = Registry.get(DLV_PATH_KEY).asString()
    override fun set(v: String) { Registry.get(DLV_PATH_KEY).setValue(v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}

/**
 * Captures the original `dlv.path` once on the first active session and restores it once
 * the last session ends. While multiple sessions are active, the backend value is
 * intentionally the most-recently-swapped value — the Go plugin reads `dlv.path` during
 * the launch path that runs synchronously inside swap(), so only the value at swap-time
 * matters per-session.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered
 * on next IDE startup.
 */
class RegistrySwapper(private val backend: RegistryBackend) {
    private var original: String? = null
    private val active = mutableSetOf<String>()
    private val lock = Any()

    fun swap(sessionId: String, newValue: String) {
        synchronized(lock) {
            if (active.isEmpty()) {
                val captured = backend.get()
                original = captured
                SentinelStore.write(captured)
            }
            active.add(sessionId)
            backend.set(newValue)
        }
    }

    fun restore(sessionId: String) {
        synchronized(lock) {
            if (!active.remove(sessionId)) return
            if (active.isEmpty()) {
                original?.let { backend.set(it) }
                original = null
                SentinelStore.clear()
            }
        }
    }
}

internal object SentinelStore {
    private val FILE: Path =
        Path.of(PathManager.getSystemPath(), "delve-per-config", "sentinel")

    fun write(original: String) {
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(FILE, original)
        } catch (_: Exception) { /* best effort */ }
    }

    fun clear() {
        try { Files.deleteIfExists(FILE) } catch (_: Exception) {}
    }

    fun readAndClear(): String? {
        return try {
            if (!Files.exists(FILE)) null
            else Files.readString(FILE).also { Files.deleteIfExists(FILE) }
        } catch (_: Exception) { null }
    }
}

@Service(Service.Level.APP)
class GlobalRegistrySwapper {
    val swapper = RegistrySwapper(PlatformRegistryBackend())
}

class RegistrySwapListener(private val project: Project) : ExecutionListener {

    private val log = thisLogger()
    private val swapper: RegistrySwapper
        get() = com.intellij.openapi.components.service<GlobalRegistrySwapper>().swapper

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val profile = env.runProfile
        if (profile !is com.intellij.execution.configurations.RunConfigurationBase<*>) return
        if (!GoConfigSupport.isSupported(profile)) return

        val s = DelvePathSettingsStore.get(profile)
        if (s.useGlobal || s.path.isBlank()) return

        val processEnv = collectEnv(profile)
        val expanded = PathExpansion.expand(s.path, project, processEnv)
        val validation = DelvePathValidator.validateExpanded(expanded, overrideEnabled = true)
        if (validation.severity == DelvePathValidator.Severity.ERROR) {
            notify(
                title = "Delve path override skipped",
                content = "${validation.message}: $expanded. Using global dlv.path.",
            )
            return
        }

        log.info("Swapping dlv.path -> $expanded for executor=$executorId")
        swapper.swap(sessionKey(env), expanded)
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        swapper.restore(sessionKey(env))
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        swapper.restore(sessionKey(env))
    }

    private fun sessionKey(env: ExecutionEnvironment): String =
        "${env.executionId}:${env.runProfile.name}"

    private fun collectEnv(profile: com.intellij.execution.configurations.RunConfigurationBase<*>): Map<String, String> {
        // Start with OS env; overlay run-config-specific env if present via reflection-free duck typing.
        // (Go run configs expose env via getCustomEnvironment(); platform-stable API.)
        val base = HashMap(System.getenv())
        val custom = (profile as? com.intellij.execution.configurations.RunConfigurationBase<*>)
            ?.let { runCatching { it::class.java.getMethod("getCustomEnvironment").invoke(it) }
                .getOrNull() as? Map<*, *> }
        custom?.forEach { (k, v) -> if (k is String && v is String) base[k] = v }
        return base
    }

    private fun notify(title: String, content: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Delve Per-Config")
            .createNotification(title, content, com.intellij.notification.NotificationType.WARNING)
            .notify(project)
    }
}
```

- [ ] **Step 4: Register the listener + notification group in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <runConfigurationExtension implementation="com.hoss.delveperconfig.DelvePathRunConfigurationExtension"/>
    <notificationGroup id="Delve Per-Config" displayType="BALLOON"/>
</extensions>

<projectListeners>
    <listener class="com.hoss.delveperconfig.RegistrySwapListener"
              topic="com.intellij.execution.ExecutionListener"/>
</projectListeners>
```

- [ ] **Step 5: Run the swapper unit tests to verify they pass**

Run: `gradle --no-daemon test --tests com.hoss.delveperconfig.RegistrySwapListenerTest`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: ExecutionListener swaps dlv.path Registry around Go debug launches"
```

---

## Task 11: Sentinel recovery on startup

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

If the IDE crashed mid-session, the Registry may still hold the swapped-in path. On startup, check the sentinel file and restore.

- [ ] **Step 1: Implement the startup activity**

```kotlin
package com.hoss.delveperconfig

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SentinelRecovery : ProjectActivity {
    override suspend fun execute(project: Project) {
        val recovered = SentinelStore.readAndClear() ?: return
        thisLogger().warn("Recovered leaked dlv.path; restoring to $recovered")
        PlatformRegistryBackend().set(recovered)
    }
}
```

- [ ] **Step 2: Register the startup activity in `plugin.xml`**

Inside the existing `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
<postStartupActivity implementation="com.hoss.delveperconfig.SentinelRecovery"/>
```

- [ ] **Step 3: Verify the project still builds**

Run: `gradle --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: startup recovery for leaked dlv.path after IDE crash"
```

---

## Task 12: Manual smoke matrix doc

**Files:**
- Create: `docs/superpowers/manual-smoke-matrix.md`

- [ ] **Step 1: Create the doc**

```markdown
# Manual Smoke Matrix

Run on each supported GoLand version (start with 2026.1+) before publishing a release.

## Setup

1. Build two distinct `dlv` binaries — `/tmp/dlv-alpha` and `/tmp/dlv-beta`. Wrap each as a shell script that writes its own path to `/tmp/dlv-invoked.log` before `exec`-ing the real dlv:

   ```sh
   #!/usr/bin/env bash
   echo "$(date -Iseconds) $0 $@" >> /tmp/dlv-invoked.log
   exec /usr/local/bin/dlv "$@"
   ```

2. Set the global `dlv.path` (Help → Find Action → Registry → `dlv.path`) to `/tmp/dlv-alpha`.

## Matrix

For each row, clear `/tmp/dlv-invoked.log`, run a Debug session, and read the log to confirm the expected binary was invoked.

| Config type | Override state | Path (raw) | Expected `/tmp/dlv-invoked.log` entry |
|---|---|---|---|
| Go Build | disabled | — | `/tmp/dlv-alpha ...` |
| Go Build | enabled | `/tmp/dlv-beta` | `/tmp/dlv-beta ...` |
| Go Build | enabled | `$PROJECT_DIR$/dlv-beta` (symlink in project) | `<project>/dlv-beta ...` |
| Go Build | enabled | `${ARM_DLV}` (env set to `/tmp/dlv-beta`) | `/tmp/dlv-beta ...` |
| Go Test | disabled | — | `/tmp/dlv-alpha ...` |
| Go Test | enabled | `/tmp/dlv-beta` | `/tmp/dlv-beta ...` |
| Concurrent | A=beta, B=alpha (start A then B before A ends) | — | A's launch uses beta; B's launch uses alpha; after both end, Registry reads `/tmp/dlv-alpha` |

## Failure-mode checks

| Scenario | Expected behavior |
|---|---|
| Override path doesn't exist on disk | Warning notification "File does not exist"; launch proceeds with global `dlv.path`. |
| Override path is set but not executable | Warning notification; launch proceeds using the path anyway (OS will fail to exec, surfacing a clear error). |
| IDE force-killed mid-debug-session | On next IDE start, `dlv.path` is restored to its pre-swap value (verify via Registry editor). |
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/manual-smoke-matrix.md
git commit -m "docs: manual smoke matrix for release verification"
```

---

## Task 13: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README**

```markdown
# Per-Config Delve Debugger Path

JetBrains GoLand / IntelliJ plugin that lets you override the Delve (`dlv`) debugger
binary on a per-run-configuration basis. Falls back to the global `dlv.path` setting
when no override is set.

Filed as [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800). Not on JetBrains'
roadmap, so this plugin fills the gap.

## Why

Cross-architecture Go development (e.g., ARMv7) needs different `dlv` binaries per
project. GoLand stores `dlv.path` globally and changing it typically needs an IDE
restart. With this plugin, each Go Build / Go Test run configuration can carry its
own override.

## Install

1. **Marketplace:** GoLand → Settings → Plugins → Marketplace → search "Per-Config Delve".
2. **From disk:** download the latest `.zip` from Releases, then Plugins → ⚙ → Install from disk.

## Use

1. Open any Go Build or Go Test run configuration.
2. Expand the **Delve** section.
3. Uncheck **Use global dlv.path** and pick a binary.
4. Save. Debug.

Paths support:

- IntelliJ macros: `$PROJECT_DIR$`, `$USER_HOME$`.
- Environment variables: `$VAR` and `${VAR}` (resolved against the launched process's environment).

## Limitations

- Supports Go Build and Go Test only. Go Remote attaches to an already-running `dlv`
  and is out of scope.
- Targets GoLand 2026.1+ for now.
- The override is shared via VCS (stored in the run config XML). Use macros and env
  vars to keep paths portable across machines.

## License

MIT.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README"
```

---

## Task 14: GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create the CI workflow**

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v3
      - run: gradle --no-daemon test buildPlugin verifyPlugin
```

- [ ] **Step 2: Create the release workflow**

```yaml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v3
      - run: gradle --no-daemon buildPlugin signPlugin publishPlugin
        env:
          CERTIFICATE_CHAIN: ${{ secrets.CERTIFICATE_CHAIN }}
          PRIVATE_KEY: ${{ secrets.PRIVATE_KEY }}
          PRIVATE_KEY_PASSWORD: ${{ secrets.PRIVATE_KEY_PASSWORD }}
          PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows
git commit -m "ci: build + sign + publish workflows"
```

---

## Task 15: Pre-release verification

- [ ] **Step 1: Run full local verification**

Run: `gradle --no-daemon test buildPlugin verifyPlugin`
Expected: BUILD SUCCESSFUL. All unit tests pass. `verifyPlugin` reports no errors against the configured IDE version.

- [ ] **Step 2: Execute the manual smoke matrix**

Open the sandbox IDE with `gradle --no-daemon runIde` and walk through every row of `docs/superpowers/manual-smoke-matrix.md`. Tick each row in the doc as you go.

- [ ] **Step 3: Tag and push**

```bash
git tag v0.1.0
git push origin main --tags
```

The Release workflow uploads the signed plugin to Marketplace. Verify the listing appears and the version matches.

- [ ] **Step 4: Update GO-19800**

Post a comment on the YouTrack issue linking to the published plugin so other users finding the issue see the workaround. (User decision — not strictly required.)
