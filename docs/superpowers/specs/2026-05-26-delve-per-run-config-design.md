# Per-Run-Configuration Delve Path Plugin — Design

- **Date:** 2026-05-26
- **Author:** Stephan Kast
- **Status:** Draft, pending review
- **Related issue:** [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800/New-Feature-Set-dlv-debugger-per-Run-Config) (Open, not on JetBrains' roadmap)

## 1. Background

GoLand stores the path to the `dlv` debugger binary as a global setting (Registry key `dlv.path`, also honored via `idea.properties`). Changing it requires editing the global setting and typically restarting the IDE before the new binary takes effect for a debug session.

Teams that debug Go code across architectures need different `dlv` binaries per project — for example, ARMv7 targets require a forked Delve build. Toggling the global setting between projects is slow and error-prone; forgetting to revert it causes silent debugger incompatibilities.

JetBrains has acknowledged the request (GO-19800) but stated it is not on the roadmap. A third-party plugin is the realistic path.

## 2. Goals

- Add a per-run-configuration field that overrides the Delve binary path for that specific configuration.
- Cover the **Go Build** and **Go Test** run/debug configuration types.
- Persist the override inside the run configuration XML so the setting commits to VCS and shares with the team.
- Fall back to the global `dlv.path` when no override is set.
- Support common path macros (`$PROJECT_DIR$`, `$USER_HOME$`) and environment variables.
- Ship as a public JetBrains Marketplace plugin targeting GoLand 2026.1+ (`since-build = 261.*`).

## 3. Non-goals

- Go Remote run configurations (they attach to a remote dlv, so a local override doesn't apply).
- Bundled or auto-installed `dlv` binaries — users supply their own.
- Multi-binary management UI (a list of named dlv binaries with per-config selection) — single path field per config in v1.
- Telemetry.
- Backporting below GoLand 2026.1.

## 4. High-level approach

The plugin lives in two halves:

1. **Settings + UI** — a `RunConfigurationExtension` adds a section to the Go Build / Go Test configuration editors, persists a small XML blob into the run config, and validates the user's input.
2. **Override mechanism** — two parallel paths that both end up making the Go plugin launch the user's chosen `dlv`:
   - **A. Extension hook:** the same `RunConfigurationExtension` implements `patchCommandLine` / `attachToProcess` if the Go plugin invokes them.
   - **B. Scoped Registry swap (fallback):** an `ExecutionListener` swaps `Registry.get("dlv.path")` immediately before a debug process is scheduled and restores it when the process terminates. Depends only on the public `dlv.path` Registry key, not on the closed Go plugin's internal call graph.

The plugin tries A first. If a runtime probe (Milestone 0) shows A's hooks don't fire for Go debug launches, B carries the load. B remains in the codebase permanently as a resilience layer against future Go-plugin refactors.

## 5. Milestone 0 — Probe build

Before committing to UI design or storage format, ship an instrumented probe that answers:

1. Does `RunConfigurationExtension.isApplicableFor` return `true` for `GoApplicationRunConfiguration` and `GoTestRunConfiguration`?
2. Are `patchCommandLine`, `attachToProcess`, `readExternal`, `writeExternal`, `createEditor`, `validateConfiguration` invoked during a debug launch?
3. In what order do they fire relative to `ExecutionListener.processStartScheduled`?
4. What concrete subclass of `RunConfigurationBase` does the Go plugin pass in (for `instanceof` checks in the production extension)?

### 5.1 Probe shape

- Kotlin class extending `RunConfigurationExtension`, registered via `com.intellij.runConfigurationExtension`.
- Each lifecycle method writes a timestamped line to `~/.cache/delve-plugin-probe/probe-<pid>.log` (created on demand) including method name, run config class, and a short hash of relevant arguments.
- An `ExecutionListener` registered on the project's message bus does the same.

### 5.2 Probe exit criteria

Run on GoLand 2026.1+ with both run config types, in **Run** and **Debug** mode, then read the log. The probe answers both:

- **UI question:** does `createEditor` fire so we can add our settings tab via the extension point? If not, we'd need a different UI strategy (wrap the existing editor via `SettingsEditorListener`, or a separate `ConfigurationType` extension). That outcome forces a spec revision before v1.
- **Launch question:** does `patchCommandLine` (or `attachToProcess`) fire on debug start, before the Go plugin reads `dlv.path`? Determines whether Path A is the primary override mechanism or Path B is.

The probe is deleted from the codebase before v1 ships; its findings are captured as inline comments in the production extension.

## 6. Detailed design

### 6.1 Plugin scaffold

- Build system: [IntelliJ Platform Gradle Plugin 2.x](https://github.com/JetBrains/intellij-platform-gradle-plugin).
- Language: Kotlin (JVM target matching platform requirement).
- Module layout: single `:plugin` module to start. No premature split.
- `plugin.xml`:
  - `<id>com.hoss.delve-per-config</id>` (final ID TBD before Marketplace submission).
  - `<depends>com.intellij.modules.platform</depends>`
  - `<depends>org.jetbrains.plugins.go</depends>`
  - `<idea-version since-build="261.*"/>` (GoLand 2026.1). `until-build` left open; tightened only if regressions appear.
- CI: GitHub Actions running `verifyPlugin`, `test`, `buildPlugin`, and `signPlugin` (signing keys via repository secrets).

### 6.2 Persisted settings model

The override lives on each Go run configuration as an extension-owned XML child:

```xml
<configuration ...>
  <!-- existing Go plugin elements -->
  <delvePathSettings useGlobal="false" path="$PROJECT_DIR$/.tools/dlv-armv7"/>
</configuration>
```

Attributes:

- `useGlobal` — boolean. When `true` or when the element is absent, the global `dlv.path` is used.
- `path` — raw user input, including any macros or env-var references. Stored unexpanded so the file is portable.

The extension reads and writes this element via `readExternal(element)` / `writeExternal(element)`. Settings are keyed onto the run config via `getUserData` for runtime access during launch.

### 6.3 UI

A new collapsible section labeled **Delve debugger** appears in the editor for Go Build and Go Test run configurations, below the existing Go-specific fields.

- Checkbox: **Use global `dlv.path`** (checked by default).
- File chooser (`TextFieldWithBrowseButton`): **Delve binary path** — disabled when the checkbox is checked.
- Inline validation (driven by `validateConfiguration`):
  - Path is non-empty when override is enabled.
  - After macro/env expansion, the file exists and is executable.
  - Soft warning if `<expanded-path> version` exits non-zero or doesn't contain "Delve" in stdout (does not block launch; surfaced as a warning banner).

### 6.4 Path expansion

The plugin expands, in order:

1. IntelliJ path macros via `PathMacroManager.getInstance(project).expandPath(...)` — covers `$PROJECT_DIR$`, `$USER_HOME$`, etc.
2. Environment variable references (`$VAR` and `${VAR}`) against the launched configuration's resolved environment (which already includes user-defined env vars from the run config).

Expansion happens at launch time only, never at save time, so the persisted XML stays portable.

### 6.5 Override mechanism

**Path A — extension hook (preferred when the probe confirms it fires):**

- The same `RunConfigurationExtension` overrides `patchCommandLine(configuration, runnerSettings, cmdLine, runnerId)`.
- If the override is enabled, the extension replaces the executable in `cmdLine` with the expanded user path, or — if the Go plugin runs `dlv` from a different command-line builder — modifies whichever parameter holds the dlv path. The probe identifies the exact mechanism.

**Path B — ExecutionListener + scoped Registry swap (always active as a safety net):**

- An `ExecutionListener` subscribed on the project bus listens for `processStartScheduled(executorId, env)`.
- If `env.runProfile` is a Go run configuration we recognize and an override is enabled, the listener:
  1. Acquires a project-scoped reentrant lock.
  2. Captures the current value of `Registry.get("dlv.path")` into a per-session record keyed by execution id.
  3. Calls `Registry.get("dlv.path").setValue(expandedPath)`.
- On `processTerminated` (or `processNotStarted`) for the same execution id, the listener restores the captured value and releases the lock.

Path B is correct even when multiple debug sessions overlap because the lock serializes the swap-and-launch window: the swap happens after `processStartScheduled` and the launch reads the value synchronously before the listener releases. If the Go plugin reads `dlv.path` asynchronously after `processStarting`, a follow-up probe iteration may show that and we'd need a different sync point — but the public lifecycle suggests reads happen during the launch path that starts on this thread.

### 6.6 Concurrent debug sessions

- Path A is naturally session-scoped (each run gets its own extension invocation).
- Path B is the risky case. The lock makes the swap window short and atomic, but it does serialize debug *starts* across the project. In practice debug sessions start one at a time per user action; the serialization is invisible.
- Tests must cover: launch debug A (override = `/path/a`), launch debug B (override = `/path/b`) while A is still running, confirm both use the right binary, then terminate both and confirm `dlv.path` is back to its pre-A value.

### 6.7 Failure modes and recovery

- **Probe shows neither A nor B fires before the dlv read.** Plugin cannot ship in current form. Re-evaluate (e.g., wrap `ProgramRunner`, BeforeRun task that mutates env).
- **Override path missing on disk at launch.** Validation catches this in the editor. If the file is removed between save and launch, the listener re-checks before swapping the Registry value; if the file is gone, it surfaces an error notification and skips the swap, letting the launch fail naturally with the unmodified global setting (rather than half-swapping the Registry).
- **`Registry.get("dlv.path")` swap leaks.** If the IDE crashes mid-session, the persisted Registry value is whatever the swap left. Mitigation: on plugin startup, check a sentinel file the plugin writes before each swap; if found, restore the saved value and delete the sentinel.

## 7. Testing

### 7.1 Automated

- Light platform tests (`BasePlatformTestCase`) covering:
  - XML round-trip: write a config with override, reload, assert values preserved.
  - Path expansion: macros, env vars, missing vars.
  - Validation outcomes: empty path, nonexistent file, non-executable file, valid file.
- Unit tests for the lock and restoration logic in Path B (mock `Registry`, simulate overlapping sessions).

### 7.2 Manual smoke matrix

Per supported GoLand version, run for both **Go Build** and **Go Test**:

| Override state | Expected dlv binary used |
|---|---|
| Disabled (default) | Global `dlv.path` |
| Enabled, valid absolute path | User-specified binary |
| Enabled, `$PROJECT_DIR$/...` macro | Expanded path's binary |
| Enabled, env var `${ARM_DLV}` | Env-resolved binary |
| Two configs with different overrides, both debugged sequentially | Each uses its own binary; global value unchanged after both end |

Verification: each manual run logs the actual `dlv` binary it invoked (probe-style file in `/tmp`) so the operator can confirm without trusting the IDE UI.

## 8. Packaging and distribution

- Marketplace listing: name, summary, two screenshots (run config editor with override field, README excerpt), license (MIT recommended).
- Plugin signing via `signPlugin` Gradle task; certificate + private key stored as GitHub Actions secrets.
- Release flow: tag → CI builds + signs → uploads to Marketplace via `publishPlugin`.
- README documents: minimum GoLand version, how the override interacts with the global setting, env-var support, known limitations (Go Remote not supported).

## 9. Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Go plugin doesn't honor `RunConfigurationExtension` for debug launches | Medium | Path B fallback is always active; verified by Milestone 0 probe. |
| Go plugin reads `dlv.path` outside the swap window | Low-medium | Probe measures the exact read point; if outside, redesign before v1. |
| GoLand 2026.2+ refactors the launch path | Medium | Re-run probe on each major release as part of release-prep checklist; smoke matrix catches behavioral regressions. |
| Extension's `createEditor` not invoked by Go plugin | Medium | Probe surfaces this before UI work begins; fallback UI strategy (editor wrapping or own ConfigurationType) decided at that point. |
| Concurrent debug sessions race the Registry swap | Low | Project-scoped lock; explicit test case. |
| Marketplace rejects plugin (e.g., touching Registry programmatically) | Low | Document the behavior clearly in the listing; respond to review comments. |

## 10. Open questions

- Plugin ID — using `com.hoss.delve-per-config` as placeholder; final ID picked before first Marketplace submission.
- License — MIT recommended unless Hoss has an internal default.
- Whether to expose the "Use global `dlv.path`" toggle as a single checkbox or a tri-state (use global / disable debug / use custom). Single checkbox proposed for v1.

## 11. Out of scope (possible future work)

- Named dlv profiles (e.g., "arm-v7", "default") selectable from a dropdown per config.
- Auto-detection of architecture-appropriate dlv binaries.
- Go Remote support (would need a different model — perhaps SSH-launching dlv on the target host).
- Support for `dlv-dap` paths separately from `dlv`.

## 12. References

- Issue: [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800/New-Feature-Set-dlv-debugger-per-Run-Config)
- [IntelliJ Platform SDK — Run Configurations](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)
- [IntelliJ Platform SDK — GoLand Plugin Development](https://plugins.jetbrains.com/docs/intellij/goland.html)
- [IntelliJ Platform Gradle Plugin 2.x](https://github.com/JetBrains/intellij-platform-gradle-plugin)
- Prior art — [Dlvx plugin](https://plugins.jetbrains.com/) (Marketplace), [VS Code Go `dlvPath` per launch.json](https://github.com/microsoft/vscode-go/pull/2308)
