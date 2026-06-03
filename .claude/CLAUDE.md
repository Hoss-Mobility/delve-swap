# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this plugin does

DelveSwap is a JetBrains **GoLand** plugin that overrides the path to the Delve (`dlv`) debugger binary on a **per-run-configuration** basis. GoLand stores `dlv.path` globally and changing it normally needs an IDE restart; this plugin lets each Go Build / Go Test run configuration (including their Run-on-Target SSH variants) carry its own override. Background: YouTrack issue [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800).

## Commands

Build / test / verify (use the Gradle wrapper):

```
./gradlew test                                           # JUnit 5 unit tests (no IDE spin-up)
./gradlew test --tests com.hoss.delveswap.DlvPathSwapListenerTest    # single class
./gradlew test --tests "*sessionKey*"                    # single test by name pattern
./gradlew buildPlugin                                    # → build/distributions/*.zip
./gradlew verifyPlugin                                   # IntelliJ Plugin Verifier (slow: pulls multiple GoLand builds)
./gradlew runIde                                         # launch sandbox GoLand with the plugin loaded
```

JDK 21 is required (the IntelliJ Platform 2026.1 plugin pins to it). `settings.gradle.kts` includes the `foojay-resolver-convention` plugin so Gradle auto-downloads a matching JDK 21 if none is installed locally. CI provides JDK 21 explicitly via `actions/setup-java@v4` and the resolver is a no-op there.

Useful gradle properties (set via `-P<name>=...` or `gradle.properties`):

- `localPlatformPath=/path/to/GoLand` — develop against an already-installed GoLand instead of downloading 2026.1 (see `build.gradle.kts`).
- `skipSearchableOptions=true` — skip the IDE-launched indexing step in `buildPlugin` for faster local iteration.
- `localPluginRepo=file:///path/to/mirror` — offline Gradle-plugin mirror.

Docker fallback build (used when the host environment can't resolve Gradle plugins, e.g. JDK/Gradle HTTP-redirect issues on Linux):

```
docker build -t delve-plugin-builder .
docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle delve-plugin-builder
```

`runIde` is intentionally not supported in the Docker image (needs X11 forwarding).

## CI / release

- `.github/workflows/ci.yml` runs `test buildPlugin verifyPlugin` on push to `main` and on PRs. It frees ~10 GB of preinstalled toolchains first because `verifyPlugin` pulls two full GoLand IDEs plus ~1.5 GB of plugin deps and overruns the default runner disk.
- `.github/workflows/release.yml` triggers on tags matching `v*` and runs `signPlugin publishPlugin`. Required secrets: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`.
- `pluginVersion` lives in `gradle.properties`. Bump it before tagging, and add a corresponding entry to `CHANGELOG.md` plus the `<change-notes>` block in `plugin.xml`.

## Toolchain pins (matter)

- **Java/Kotlin: 21** (`build.gradle.kts`).
- **GoLand 2026.1+ / build 261** (`platformVersion`, `pluginSinceBuild`), capped at `262.*` (`pluginUntilBuild` in `gradle.properties`). Bump the cap as new GoLand releases ship — `untilBuild = null` would silently crash users on a future incompatible GoLand instead of disabling the plugin.
- **IntelliJ Plugin Verifier pinned to 1.405** via `pluginVerifierVersion`. The version bundled with the IntelliJ-Platform Gradle plugin crashes with `ClosedFileSystemException` when verifying against multiple GoLand builds in parallel; this pin is the workaround. Don't bump downward.

## Architecture — the big picture

The whole plugin exists because the GoLand Go plugin reads `dlv.path` from **`System.getProperty("dlv.path")` at debug-launch time** (`com.goide.execution.GoRunUtil.dlv(TargetEnvironmentRequest)`), not from any IntelliJ Registry key. Three things flow from that:

1. **The "swap" is a JVM system-property mutation.** `SystemPropertyBackend` implements the `DlvPathBackend` interface; tests substitute an in-memory fake. `dlv.path` is *not* an IntelliJ Registry key (the Go plugin never declares it as one), so `Registry.get("dlv.path")` would throw on a fresh IDE. (Earlier revisions named these `Registry*` — the rename to `DlvPath*` happened post-1.0 so the names stop lying.)

2. **Swap on debug start, restore on debug end** — that's the entire mechanism. `DlvPathSwapListener` (an `ExecutionListener` registered as a `projectListener`) does both. The Go plugin reads `dlv.path` synchronously at the moment of `startProcess`, so the value present then is what that session uses.

3. **Run-on-Target SSH debug fires two `ExecutionEnvironment`s back-to-back** — a fast build sub-execution (~200 ms), then the real debug. If we restored after the first, the second would read the wrong value. Mitigation: stash the override on the env's `UserData` under `com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY` in `processStartScheduled`; re-apply it in `processStarting` of the (Go-plugin-copied) second env, routed through the ref-counted swapper so it's balanced by that env's own `processTerminated`.

### 3-tier source resolution

Each Go config picks one of three `DelveSource` values (`DelvePathSettings.kt`). The full settings→value mapping is the pure `resolveForLaunch(settings, baseline, expandedPath): Resolution` function in `DlvPathSwapListener.kt`; `Resolution` carries the value to assert plus an optional `Notice` the listener fires.

| Source    | Resolves to                              | Notes                                                                          |
|-----------|------------------------------------------|--------------------------------------------------------------------------------|
| `BUNDLED` | `""` (empty)                             | Empty makes the Go plugin fall back to its shipped `dlv`.                      |
| `GLOBAL`  | `DlvPathSwapper.baseline`                 | The IDE-wide `dlv.path`. Re-read each call when no sessions are active, so editing it mid-IDE-lifetime takes effect immediately. |
| `CUSTOM`  | expanded + validated path                 | Macros (`$PROJECT_DIR$`, `$USER_HOME$`) then env vars (`$VAR`, `${VAR}`).      |

Critical invariant: **every supported config asserts its resolved value**, including `GLOBAL` and `BUNDLED`. An older version of the listener early-returned for non-CUSTOM configs and left `dlv.path` untouched, which caused a concurrent session's swap to leak into the next session (the "cross-project leak" fixed in #3). When CUSTOM validation fails with `ERROR`, fall back to `swapper.baseline`, not to "untouched" — same reason.

### Overlapping sessions and crash recovery

`DlvPathSwapper` is a small ref-counted wrapper:

- Captures `original` only when the **first** session becomes active.
- Restores `original` only when the **last** session ends.
- `baseline` returns the captured `original` while sessions are active (so a concurrent GLOBAL session asserts the user's setting, not a sibling's swap) and re-reads the backend when none are active.
- While ≥2 sessions overlap, the backend value is the **most-recently-swapped** value (last-writer-wins).
- **Known race:** two sessions scheduled close enough that both `swap()` calls run before either `startProcess` will both see the second swap's value. Rare in practice; a per-session bridge is tracked as a follow-up plan (see `docs/superpowers/`).

`SentinelStore` (instance-based, with `default()` for production and constructor injection for tests) writes the captured `original` to `<system>/delveswap/sentinel` on first swap and deletes it on final restore. `SentinelRecovery` (a `ProjectActivity`, `backgroundPostStartupActivity`) reads it on next IDE start to recover from a crash mid-session. It is gated by:

- An `AtomicBoolean firstRun` so multiple opened projects don't each fire it.
- A `swapper.hasActiveSessions()` skip so a *live* session's sentinel (not a crash artifact) isn't trampled.
- A blank sentinel is interpreted as "original was absent" → `Clear`, not as `ERROR_EMPTY` → discard. The `recoveryAction` function encodes this as a sealed interface (`None`/`Clear`/`Restore`/`Discard`).

### How the editor tab gets registered

`DelvePathRunConfigurationExtension` is registered under the **Go plugin's** extension point `com.goide.runConfigurationExtension` (in `plugin.xml`), NOT the platform's `com.intellij.runConfigurationExtension`. The Go run-config editors only render editors contributed through the Go-specific point. The base class `GoRunConfigurationExtension` already implements the platform's abstract `patchCommandLine`, so this class only supplies `isApplicableFor` / `isEnabledFor` plus UI + XML hooks. The Go plugin launches `dlv` from `com.goide.dlv.*` reading the global `dlv.path` — **not** via `GoExecutor`'s command line — which is why the actual override happens in `DlvPathSwapListener` at debug-launch time and not via `patchCommandLine`.

### Persistence

`DelvePathSettings` is an immutable `data class`; `writeTo` serializes `<delvePathSettings source="…" path="…"/>` into the run-config XML and `readFrom` (companion) parses it back. The default state (`GLOBAL` + empty path) writes **no** element to keep XML diffs clean. There's a backward-compat shim for v0.1.0 configs that persisted a `useGlobal` boolean.

`DelvePathRunConfigurationExtension.getSerializationId()` is frozen at the legacy string `"com.hoss.delveperconfig"` so v0.1.0 user run-config XMLs keep deserializing into this extension after the package rename. Don't change it without a migration story.

Per-config settings are kept on the run config via `RunConfigurationBase.putUserData(KEY, …)` (`DelvePathSettingsStore`), not in a project-level service.

### Files at a glance

```
src/main/kotlin/com/hoss/delveswap/
├── DelvePathSettings.kt           # immutable data class + XML serialization (+ legacy useGlobal shim)
├── DelvePathSettingsStore.kt      # per-RunConfig UserData store
├── DelvePathSettingsEditor.kt     # the "Delve" run-config tab UI
├── DelvePathRunConfigurationExtension.kt  # registers the tab under com.goide.runConfigurationExtension
├── DelvePathValidator.kt          # exists + executable, returns OK/WARN/ERROR
├── PathExpansion.kt               # $PROJECT_DIR$ / $USER_HOME$ macros, then $VAR/${VAR}
├── DlvPathSwapListener.kt         # the ExecutionListener + DlvPathSwapper + SentinelStore +
│                                  # GlobalDlvPathSwapper service + pure resolveForLaunch
│                                  # + top-level isSupportedGoConfig (formerly GoConfigSupport)
└── SentinelRecovery.kt            # ProjectActivity: replays sentinel on next IDE start
src/test/kotlin/com/hoss/delveswap/    # JUnit 5; all pure-logic, no IDE bootstrap
src/main/resources/META-INF/plugin.xml      # extension-point wiring (Go-specific EP)
docs/superpowers/{plans,specs}/             # dated design docs for in-flight changes (historical;
                                            # mentions older class names like RegistrySwap*)
```

## Test strategy

Tests are **pure JUnit 5**, no `BasePlatformTestCase` / no IDE harness. The codebase factors out pure functions (`resolveDesiredValue`, `resolveForLaunch`, `recoveryAction`, `sessionKeyFor`, `PathExpansion.expandEnv`, `DelvePathValidator.validateExpanded`) and uses a `TestDlvPathBackend` fake to drive `DlvPathSwapper` directly. `SentinelStore` takes a path constructor argument so tests use a `@TempDir`. Keep new logic factored this way — anything that needs an IDE-spinning test is a smell here.

## Trust boundary (important)

The override path is stored in the run-configuration XML, which is committed to VCS. **A teammate who can commit run-config XML can therefore set the Delve binary path you'll launch.** The plugin validates exists+executable before swapping, so an attacker would still need to land a binary at the resolved path. For shared run configurations prefer `$PROJECT_DIR$/...` paths (so the binary lives inside the reviewed project tree) and avoid `$USER_HOME$/...`.

## Known gaps / non-goals

- **`$PROJECT_DIR$`-aware file picker:** `DelvePathSettingsEditor.createEditor` passes `null` for the Project to `addBrowseFolderListener` because the Project isn't available at editor-build time. Plumbing it through `resetEditorFrom` requires re-registering the listener; deemed not worth the complexity for the UX gain. Macro expansion at debug time still works.
- **i18n / message bundle:** all UI strings are hardcoded. Small plugin, low priority.
- **Per-session bridge for the concurrent-swap race:** see `RegistrySwapper` KDoc in `DlvPathSwapListener.kt` and the README Limitations section.
