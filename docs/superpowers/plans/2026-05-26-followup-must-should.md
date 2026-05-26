# Follow-up Plan — Marketplace "Should-Do" + Code-Quality Polish

> Companion to `2026-05-26-delve-per-run-config.md` (the primary plan). Addresses the items deferred during the multi-agent final review on commits `791fb81` and `a718c09`.

**Goal:** Close the remaining Marketplace "Should-Do" gaps and the code-quality minor findings that don't require manual interaction (no screenshots, no icon design, no probe runs).

**Branch:** `feature/plugin-implementation`. Current HEAD: `a718c09`.

**Tech Stack:** same as the primary plan (Kotlin / IntelliJ Platform Gradle Plugin 2.x / GoLand 2026.1+).

---

## Out of scope (still deferred)

- **Marketplace SD-2** (verify packed-zip XML attribute) — requires a successful gradle build the local env can't run; covered when CI runs.
- **Marketplace N-1** (plugin icon) — needs asset design; defer to a polish pass.
- **Marketplace SD-5 screenshots** — need the running plugin to capture; defer until user runs `gradle runIde`.
- **Security Finding 1 mitigation (path allowlist / trust-on-first-use UX)** — architecturally significant; v0.2. This plan only adds a trust-boundary note in the README.
- **Code-quality m5, m6** — m5 (removing `overrideEnabled` param) reduces testability for marginal API win; m6 (regex edge cases) doesn't change behavior. Defer.

---

## Task F-A — Migrate `postStartupActivity` to `backgroundPostStartupActivity`

**File:**
- Modify: `src/main/resources/META-INF/plugin.xml`

Marketplace reviewer (SD-7) flagged that `postStartupActivity` is the soft-deprecated registration in modern Platform versions. The Kotlin class already implements `ProjectActivity` (the modern coroutine interface), so only the XML tag changes.

- [ ] **Step 1: Edit `plugin.xml`**

Find:
```xml
<postStartupActivity implementation="com.hoss.delveperconfig.SentinelRecovery"/>
```

Replace with:
```xml
<backgroundPostStartupActivity implementation="com.hoss.delveperconfig.SentinelRecovery"/>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "chore: use backgroundPostStartupActivity (current EP in Platform 2026.1)"
```

---

## Task F-B — CI/release workflow polish

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Update `ci.yml` so PR checks upload the built plugin zip**

Replace the file contents with:

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
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: plugin-zip
          path: build/distributions/*.zip
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: verifier-report
          path: build/reports/pluginVerifier/**
```

- [ ] **Step 2: Update `release.yml` so `verifyPlugin` runs before `publishPlugin`**

Replace the file contents with:

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
      - run: gradle --no-daemon buildPlugin verifyPlugin signPlugin publishPlugin
        env:
          CERTIFICATE_CHAIN: ${{ secrets.CERTIFICATE_CHAIN }}
          PRIVATE_KEY: ${{ secrets.PRIVATE_KEY }}
          PRIVATE_KEY_PASSWORD: ${{ secrets.PRIVATE_KEY_PASSWORD }}
          PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml
git commit -m "ci: upload plugin + verifier artifacts; verifyPlugin before publish"
```

---

## Task F-C — README expansion + trust-boundary note

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the file contents**

```markdown
# Per-Config Delve Debugger Path

JetBrains GoLand plugin that lets you override the Delve (`dlv`) debugger binary
on a per-run-configuration basis. Falls back to the global `dlv.path` setting
when no override is set.

Filed as [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800). Not on
JetBrains' roadmap, so this plugin fills the gap.

## Why

Cross-architecture Go development (e.g., ARMv7) needs different `dlv` binaries
per project. GoLand stores `dlv.path` globally and changing it typically needs
an IDE restart. With this plugin, each Go Build / Go Test run configuration can
carry its own override.

## Requirements

- GoLand **2026.1** (build 261) or newer.
- A working `dlv` binary on disk for each override path you configure.

## Install

1. **Marketplace:** GoLand → Settings → Plugins → Marketplace → search
   "Per-Config Delve".
2. **From disk:** download the latest `.zip` from Releases, then
   Plugins → ⚙ → Install plugin from disk.

## Use

1. Open any Go Build or Go Test run configuration.
2. Expand the **Delve** section.
3. Uncheck **Use global dlv.path** and pick a binary.
4. Save. Debug.

Paths support:

- IntelliJ path macros: `$PROJECT_DIR$`, `$USER_HOME$`.
- Environment variables: `$VAR` and `${VAR}` — resolved against the launched
  process's environment (OS env plus the run config's custom env vars).

## How it works

Before each Go debug launch, an `ExecutionListener` reads the override from the
run configuration, expands macros and env vars, validates the resulting path,
and temporarily swaps the IDE's global `dlv.path` Registry value. The original
value is restored as soon as the debug process terminates. A sentinel file is
written before the swap so the original value can be recovered automatically
on next IDE start if the IDE crashes mid-session.

Concurrent debug sessions are tracked with a small reference count: the
original `dlv.path` is captured on the first active session and only restored
after the last one ends. During overlapping sessions the Registry value shows
the most-recently-swapped path, which is harmless because the Go plugin reads
`dlv.path` synchronously at launch time, not continuously.

## Limitations

- **Go Build and Go Test only.** Go Remote attaches to an already-running
  `dlv` on a remote host, so a local-binary override doesn't apply.
- **Targets GoLand 2026.1+.** Older versions are not supported in v0.1.
- **Concurrent debug sessions:** while two sessions are in flight, the Registry
  briefly holds the second session's path; this is invisible in practice but
  worth knowing if you depend on inspecting `dlv.path` mid-debug.

## Trust boundary (important for shared repos)

The override path is stored in the run configuration XML, which is committed
to VCS. **A teammate who can commit run-configuration XML can therefore set
the Delve binary path you'll launch.** The plugin checks that the file exists
and is executable before swapping, so an attacker would need to land a binary
at the resolved path (and not just commit a path) for this to result in code
execution.

To stay safe:

- Use `$PROJECT_DIR$/...` paths so the binary must live inside the
  reviewed-by-VCS project tree.
- Avoid `$USER_HOME$/...` paths on team-shared run configurations.
- Treat foreign run-configuration XML the same way you treat any other code
  in the repo — review it before opening the project.

## License

[MIT](LICENSE).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: expand README with Requirements, How it works, trust-boundary note"
```

---

## Task F-D — Extract `DelvePathSettingsStore` to its own file

**Files:**
- Create: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsStore.kt`
- Modify: `src/main/kotlin/com/hoss/delveperconfig/DelvePathRunConfigurationExtension.kt`

The store is referenced from the extension, the editor, and the listener. Putting it in the extension's file means readers of any consumer have to know to look there.

- [ ] **Step 1: Create `DelvePathSettingsStore.kt`**

```kotlin
package com.hoss.delveperconfig

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.util.Key

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
```

- [ ] **Step 2: Remove the `object DelvePathSettingsStore { ... }` block from `DelvePathRunConfigurationExtension.kt`**

Delete the leading `object` declaration (including its `Key` and two methods). Remove the now-unused imports if any (`com.intellij.openapi.util.Key`).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsStore.kt
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathRunConfigurationExtension.kt
git commit -m "refactor: extract DelvePathSettingsStore to its own file"
```

---

## Task F-E — Validator polish (warning notification + Windows note + type-id source)

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`
- Modify: `src/main/kotlin/com/hoss/delveperconfig/DelvePathValidator.kt`
- Modify: `src/main/kotlin/com/hoss/delveperconfig/GoConfigSupport.kt`

- [ ] **Step 1: Notify on WARN-level validation results**

In `RegistrySwapListener.kt`'s `processStartScheduled`, find:

```kotlin
        val validation = DelvePathValidator.validateExpanded(expanded, overrideEnabled = true)
        if (validation.severity == DelvePathValidator.Severity.ERROR) {
            notify(
                title = "Delve path override skipped",
                content = "${validation.message}: $expanded. Using global dlv.path.",
            )
            return
        }
```

Replace with:

```kotlin
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
```

- [ ] **Step 2: Add Windows behavior note to the validator**

In `DelvePathValidator.kt`, find:

```kotlin
        if (!Files.isExecutable(p)) return Result.WARN_NOT_EXECUTABLE
```

Add a comment immediately above:

```kotlin
        // Note: Files.isExecutable() on Windows reflects the file's "readable" attribute,
        // not POSIX-style execute permission. On Windows this check will almost always pass.
```

- [ ] **Step 3: Document the source of the hardcoded type IDs**

In `GoConfigSupport.kt`, replace:

```kotlin
object GoConfigSupport {
    private val SUPPORTED_TYPE_IDS = setOf(
        "GoApplicationRunConfiguration",  // Go Build
        "GoTestRunConfiguration",          // Go Test
    )
```

With:

```kotlin
object GoConfigSupport {
    // Type IDs come from the closed-source JetBrains Go plugin
    // (`GoApplicationRunConfigurationType.getId()` and the test equivalent).
    // They are stable across Go plugin releases in 261.* but should be
    // re-verified on each major GoLand/Go-plugin upgrade — the Milestone 0
    // probe in plan section 5 captures the current values at runtime.
    private val SUPPORTED_TYPE_IDS = setOf(
        "GoApplicationRunConfiguration",  // Go Build
        "GoTestRunConfiguration",          // Go Test
    )
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt
git add src/main/kotlin/com/hoss/delveperconfig/DelvePathValidator.kt
git add src/main/kotlin/com/hoss/delveperconfig/GoConfigSupport.kt
git commit -m "chore: surface WARN validation; document Windows exec semantics + Go type-id source"
```

---

## Task F-F — Final consolidated review

After F-A through F-E land, dispatch one spec-compliance and one code-quality reviewer over the diff `a718c09..HEAD`. Apply any minor fixes inline. No new tasks; this is the gate before the user pushes.
