# 3-Tier Delve Path Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-config "Use global dlv.path" checkbox with a leak-proof 3-tier choice (GoLand bundled delve / Global dlv.path / Custom path) that is actively asserted per debug session, so a concurrent project's Delve binary can never leak into another session.

**Architecture:** A per-config `DelveSource` enum drives a pure resolver that maps each tier to the `dlv.path` value to assert: `BUNDLED → ""`, `GLOBAL → IDE baseline captured at startup`, `CUSTOM → validated/expanded path`. `RegistrySwapListener` drops its early-return and always asserts the resolved value through the existing ref-counted `RegistrySwapper`. The swapper captures the true IDE baseline at construction (≈ IDE startup) for `GLOBAL` to resolve against.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (GoLand), JUnit 5, JDOM (settings persistence), Gradle (run via the project's Docker image).

---

## Conventions

**Test command** (no local gradle/gradlew — this repo builds in Docker; build the image once):

```bash
docker build -t delve-plugin-builder .
```

Run the full unit-test suite:

```bash
docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle \
  delve-plugin-builder test
```

Run a single test class (append a Gradle `--tests` filter to the `test` task):

```bash
docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle \
  delve-plugin-builder test --tests "com.hoss.delveperconfig.DelvePathSettingsTest"
```

All paths are relative to repo root `/home/skast/vault/projects/github/delve-swap`.

---

## Task 1: Introduce `DelveSource` and migrate `DelvePathSettings`

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt`
- Test: `src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt`

- [ ] **Step 1: Rewrite the settings tests (failing)**

Replace the entire body of `src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt` with:

```kotlin
package com.hoss.delveperconfig

import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelvePathSettingsTest {
    @Test
    fun `default settings use GLOBAL source and empty path`() {
        val s = DelvePathSettings()
        assertEquals(DelveSource.GLOBAL, s.source)
        assertEquals("", s.path)
    }

    @Test
    fun `custom source round-trips source and path`() {
        val s = DelvePathSettings(source = DelveSource.CUSTOM, path = "\$PROJECT_DIR\$/dlv")
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.CUSTOM, loaded.source)
        assertEquals("\$PROJECT_DIR\$/dlv", loaded.path)
    }

    @Test
    fun `bundled source round-trips`() {
        val s = DelvePathSettings(source = DelveSource.BUNDLED)
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.BUNDLED, loaded.source)
    }

    @Test
    fun `absent element decodes to defaults`() {
        val parent = Element("configuration")
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.GLOBAL, loaded.source)
        assertEquals("", loaded.path)
    }

    @Test
    fun `writeTo with default values writes no element`() {
        val parent = Element("configuration")
        DelvePathSettings().writeTo(parent)
        assertEquals(true, parent.children.isEmpty())
    }

    @Test
    fun `legacy useGlobal=false migrates to CUSTOM`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute("useGlobal", "false")
                .setAttribute(DelvePathSettings.ATTR_PATH, "/opt/dlv-arm"),
        )
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.CUSTOM, loaded.source)
        assertEquals("/opt/dlv-arm", loaded.path)
    }

    @Test
    fun `legacy useGlobal=true migrates to GLOBAL`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute("useGlobal", "true")
                .setAttribute(DelvePathSettings.ATTR_PATH, ""),
        )
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.GLOBAL, loaded.source)
    }

    @Test
    fun `new source attribute takes precedence over legacy useGlobal`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute(DelvePathSettings.ATTR_SOURCE, "BUNDLED")
                .setAttribute("useGlobal", "false"),
        )
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertEquals(DelveSource.BUNDLED, loaded.source)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.DelvePathSettingsTest"`
Expected: FAIL — compilation error (`DelveSource` unresolved, `source` not a member, `ATTR_SOURCE` unresolved).

- [ ] **Step 3: Rewrite `DelvePathSettings.kt`**

Replace the entire contents of `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt` with:

```kotlin
package com.hoss.delveperconfig

import org.jdom.Element

/** Where a run config gets its Delve binary. */
enum class DelveSource { BUNDLED, GLOBAL, CUSTOM }

class DelvePathSettings(
    var source: DelveSource = DelveSource.GLOBAL,
    var path: String = "",
) {
    fun writeTo(parent: Element) {
        if (source == DelveSource.GLOBAL && path.isEmpty()) return  // default state — no element
        val el = Element(ELEMENT_NAME).apply {
            setAttribute(ATTR_SOURCE, source.name)
            setAttribute(ATTR_PATH, path)
        }
        parent.addContent(el)
    }

    fun readFrom(parent: Element) {
        val el = parent.getChild(ELEMENT_NAME) ?: return
        val sourceAttr = el.getAttributeValue(ATTR_SOURCE)
        source = when {
            sourceAttr != null ->
                runCatching { DelveSource.valueOf(sourceAttr) }.getOrDefault(DelveSource.GLOBAL)
            // Legacy v1.x configs persisted a `useGlobal` boolean instead of `source`.
            el.getAttributeValue(ATTR_USE_GLOBAL_LEGACY)?.toBoolean() == false -> DelveSource.CUSTOM
            else -> DelveSource.GLOBAL
        }
        path = el.getAttributeValue(ATTR_PATH) ?: ""
    }

    fun copy(): DelvePathSettings = DelvePathSettings(source, path)

    companion object {
        const val ELEMENT_NAME = "delvePathSettings"
        const val ATTR_SOURCE = "source"
        const val ATTR_PATH = "path"
        const val ATTR_USE_GLOBAL_LEGACY = "useGlobal"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.DelvePathSettingsTest"`
Expected: PASS (8 tests). NOTE: the full build will still fail to compile until Tasks 4 & 6 update the editor/listener that reference the old `useGlobal` field — that is expected at this point; the single-class test compiles its own slice.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathSettings.kt \
        src/test/kotlin/com/hoss/delveperconfig/DelvePathSettingsTest.kt
git commit -m "feat: 3-way DelveSource settings with legacy useGlobal migration"
```

---

## Task 2: Capture the IDE baseline in `RegistrySwapper`

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` (the `RegistrySwapper` class)
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Add the failing baseline test**

Append this test inside the `RegistrySwapListenerTest` class (before the closing brace, after `hasActiveSessions reflects swap and restore`):

```kotlin
    @Test
    fun `baseline captures the backend value at construction`() {
        val swapper = RegistrySwapper(TestRegistryBackend("/usr/bin/dlv"))
        assertEquals("/usr/bin/dlv", swapper.baseline)

        // Baseline is immutable — swapping does not change it.
        swapper.swap("a", "/opt/dlv-arm")
        assertEquals("/usr/bin/dlv", swapper.baseline)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: FAIL — compilation error (`baseline` unresolved).

- [ ] **Step 3: Add the `baseline` property**

In `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`, change the `RegistrySwapper` class header from:

```kotlin
class RegistrySwapper(private val backend: RegistryBackend) {
    private var original: String? = null
```

to:

```kotlin
class RegistrySwapper(private val backend: RegistryBackend) {
    /**
     * The `dlv.path` value present before any session swapped — captured once at
     * construction (≈ IDE startup for the app-level [GlobalRegistrySwapper]). This is what
     * the GLOBAL tier resolves to, and it never changes for the lifetime of the swapper.
     */
    val baseline: String = backend.get()
    private var original: String? = null
```

- [ ] **Step 4: Run it to verify it passes**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS (existing swapper tests + the new baseline test).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt \
        src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt
git commit -m "feat: capture immutable IDE baseline in RegistrySwapper"
```

---

## Task 3: Pure `resolveDesiredValue` resolver + leak regression test

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` (add a top-level internal function)
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Add failing resolver + leak-regression tests**

Append these tests inside the `RegistrySwapListenerTest` class (before the closing brace):

```kotlin
    @Test
    fun `resolveDesiredValue maps each source to its asserted value`() {
        assertEquals("", resolveDesiredValue(DelveSource.BUNDLED, "/baseline/dlv", "/custom/dlv"))
        assertEquals("/baseline/dlv", resolveDesiredValue(DelveSource.GLOBAL, "/baseline/dlv", "/custom/dlv"))
        assertEquals("/custom/dlv", resolveDesiredValue(DelveSource.CUSTOM, "/baseline/dlv", "/custom/dlv"))
    }

    @Test
    fun `a GLOBAL session asserts baseline instead of a concurrent custom session's leaked path`() {
        // Reproduces the reported bug: Project A (custom ARM) is debugging; Project B (global)
        // starts and must NOT inherit A's ARM binary.
        val backend = TestRegistryBackend("")          // no IDE-wide dlv.path -> baseline is empty
        val swapper = RegistrySwapper(backend)

        // Project A — custom ARM path — starts and swaps.
        swapper.swap("A", resolveDesiredValue(DelveSource.CUSTOM, swapper.baseline, "/opt/dlv-arm"))
        assertEquals("/opt/dlv-arm", backend.get())

        // Project B — GLOBAL — starts while A is still active. It asserts the baseline,
        // overwriting the leaked ARM path for its own startProcess read.
        swapper.swap("B", resolveDesiredValue(DelveSource.GLOBAL, swapper.baseline, ""))
        assertEquals("", backend.get())                // B sees the bundled/global default, not ARM

        // B ends first; A is still active so nothing is restored yet.
        swapper.restore("B")
        // A ends last; property returns to the captured baseline.
        swapper.restore("A")
        assertEquals("", backend.get())
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: FAIL — compilation error (`resolveDesiredValue` unresolved).

- [ ] **Step 3: Add the resolver function**

In `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`, add this top-level function next to `sessionKeyFor` (near the bottom of the file, outside any class):

```kotlin
/**
 * Resolve the `dlv.path` value a config wants asserted for its session.
 *
 *  - [DelveSource.BUNDLED] -> `""`  — empty makes the Go plugin fall back to its shipped dlv.
 *  - [DelveSource.GLOBAL]  -> the IDE [baseline] captured at startup.
 *  - [DelveSource.CUSTOM]  -> the already-expanded, already-validated custom path.
 *
 * Pure (no IDE deps) so the resolution table is unit-testable in isolation.
 */
internal fun resolveDesiredValue(
    source: DelveSource,
    baseline: String,
    expandedCustomPath: String,
): String = when (source) {
    DelveSource.BUNDLED -> ""
    DelveSource.GLOBAL -> baseline
    DelveSource.CUSTOM -> expandedCustomPath
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS (all swapper tests + resolver + leak-regression).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt \
        src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt
git commit -m "feat: pure resolveDesiredValue resolver + concurrent-leak regression test"
```

---

## Task 4: Wire the listener to assert the resolved value for every supported config

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` (the `RegistrySwapListener.processStartScheduled` method + a new private `computeDesired` helper)

This task removes the early-return that caused the leak. There is no new unit test (the logic now delegates to `resolveDesiredValue` + `RegistrySwapper`, both already covered); correctness is verified by compiling and by the leak-regression test from Task 3.

- [ ] **Step 1: Replace `processStartScheduled` and add `computeDesired`**

In `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`, replace the entire `processStartScheduled` method:

```kotlin
    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val profile = env.runProfile
        if (profile !is com.intellij.execution.configurations.RunConfigurationBase<*>) return
        if (!GoConfigSupport.isSupported(profile)) return

        val s = DelvePathSettingsStore.get(profile)
        if (s.useGlobal || s.path.isBlank()) return

        val processEnv = collectEnv(profile)
        val expanded = PathExpansion.expand(s.path, project, processEnv)
        val validation = DelvePathValidator.validateExpanded(expanded, overrideEnabled = true)
        when (validation.severity) {
            DelvePathValidator.Severity.ERROR -> {
                notify(
                    title = "Delve path override skipped",
                    content = "${validation.message}: $expanded. Using global dlv.path.",
                )
                return
            }
            DelvePathValidator.Severity.WARN -> {
                notify(
                    title = "Delve path warning",
                    content = "${validation.message}: $expanded. Attempting launch anyway.",
                )
                // fall through to swap — OS will surface a clearer error if exec fails.
            }
            DelvePathValidator.Severity.OK -> Unit
        }

        log.info("Swapping dlv.path -> $expanded for executor=$executorId")
        swapper.swap(sessionKey(env), expanded)
        env.putUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY, expanded)
    }
```

with:

```kotlin
    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val profile = env.runProfile
        if (profile !is com.intellij.execution.configurations.RunConfigurationBase<*>) return
        if (!GoConfigSupport.isSupported(profile)) return

        // Every supported config asserts its resolved value — including GLOBAL and BUNDLED.
        // The old early-return left dlv.path untouched, so a non-custom config inherited
        // whatever a concurrent session had swapped in (the reported cross-project leak).
        val s = DelvePathSettingsStore.get(profile)
        val desired = computeDesired(s, profile)

        log.info("Asserting dlv.path -> '$desired' for executor=$executorId (source=${s.source})")
        swapper.swap(sessionKey(env), desired)
        // Carry a non-empty value onto the second env of a Run-on-Target debug, which reads
        // CUSTOM_DELVE_PATH_KEY rather than the system property. An empty value (BUNDLED, or
        // GLOBAL with no IDE-wide dlv.path) means "no override", so we leave the key unset.
        if (desired.isNotEmpty()) {
            env.putUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY, desired)
        }
    }

    /**
     * Resolve the dlv.path this config should assert. CUSTOM paths are expanded and validated;
     * on a validation ERROR we fall back to the baseline (the GLOBAL value) rather than leaving
     * dlv.path untouched, so the fallback is itself leak-proof.
     */
    private fun computeDesired(
        s: DelvePathSettings,
        profile: com.intellij.execution.configurations.RunConfigurationBase<*>,
    ): String {
        if (s.source != DelveSource.CUSTOM) {
            return resolveDesiredValue(s.source, swapper.baseline, "")
        }
        val processEnv = collectEnv(profile)
        val expanded = PathExpansion.expand(s.path, project, processEnv)
        val validation = DelvePathValidator.validateExpanded(expanded, overrideEnabled = true)
        return when (validation.severity) {
            DelvePathValidator.Severity.ERROR -> {
                notify(
                    title = "Delve path override skipped",
                    content = "${validation.message}: $expanded. Falling back to global dlv.path.",
                )
                swapper.baseline
            }
            DelvePathValidator.Severity.WARN -> {
                notify(
                    title = "Delve path warning",
                    content = "${validation.message}: $expanded. Attempting launch anyway.",
                )
                resolveDesiredValue(DelveSource.CUSTOM, swapper.baseline, expanded)
            }
            DelvePathValidator.Severity.OK ->
                resolveDesiredValue(DelveSource.CUSTOM, swapper.baseline, expanded)
        }
    }
```

- [ ] **Step 2: Verify it compiles (run the suite)**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS — the listener compiles against the new settings model and resolver. (`DelvePathSettingsEditor` still references `useGlobal` and will block a *full* `buildPlugin`; the editor is fixed in Task 6.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt
git commit -m "fix: assert resolved dlv.path for every supported config (kills cross-project leak)"
```

---

## Task 5: Replace the checkbox UI with a 3-way radio

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt`

No unit test (Swing UI). Verified by full compile in Task 6.

- [ ] **Step 1: Rewrite the editor**

Replace the entire contents of `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt` with:

```kotlin
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
```

- [ ] **Step 2: Run the FULL build to verify everything compiles and tests pass**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test buildPlugin`
Expected: PASS — all unit tests green AND the plugin zip builds (no remaining `useGlobal` references anywhere). If `buildPlugin` complains about searchable options, re-run with `-PskipSearchableOptions=true` appended.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt
git commit -m "feat: 3-way radio (bundled / global / custom) in run-config UI"
```

---

## Task 6: Update README and run the full verifier

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README**

Find the section in `README.md` describing the "Use global dlv.path" checkbox / per-config setting (search for `dlv.path` and `global`). Replace its description of the two-state checkbox with the three options. Use this wording (adapt headings to match the file's existing style):

```markdown
Each run configuration chooses its Delve binary from three options:

- **GoLand bundled delve** — always use the `dlv` shipped with GoLand, ignoring any IDE-wide `dlv.path`.
- **Global dlv.path** (default) — use the IDE-wide `dlv.path` captured at startup (equivalent to GoLand's bundled delve when none is configured).
- **Custom** — use a specific `dlv` binary; supports `$PROJECT_DIR$`, `$USER_HOME$`, and `$VAR` / `${VAR}` env vars.

The chosen value is asserted per debug session, so debugging two projects with different Delve binaries at the same time no longer leaks one project's binary into the other.

> Known limitation: a *Run-on-Target* (SSH) debug set to **GoLand bundled delve** while an IDE-wide `dlv.path` is configured may still use the configured global path, because the Run-on-Target path carries an explicit override rather than an "unset" signal. Local debugging is unaffected.
```

- [ ] **Step 2: Run the full release-equivalent verification**

Run: `docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder test buildPlugin verifyPlugin`
Expected: PASS — tests green, plugin zip built, plugin verifier reports no new problems.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: describe 3-tier delve binary selection and Run-on-Target caveat"
```

---

## Self-Review Notes (verified while writing)

- **Spec coverage:** 3-tier model → Tasks 1/3/4/5; baseline capture → Task 2; backward-compat migration → Task 1; per-session assertion / leak fix → Task 4; UI radio → Task 5; validation fallback to baseline → Task 4; leak regression test → Task 3; README → Task 6.
- **Type consistency:** `DelveSource{BUNDLED,GLOBAL,CUSTOM}`, `DelvePathSettings.source`, `RegistrySwapper.baseline`, and `resolveDesiredValue(source, baseline, expandedCustomPath)` are referenced identically across Tasks 1–5.
- **Verification items from the spec** are resolved in code: `BUNDLED → ""` relies on the documented empty≡bundled behavior; the `CUSTOM_DELVE_PATH_KEY` Run-on-Target caveat for BUNDLED+non-empty-baseline is documented as a known limitation in Task 6 rather than left silent.
