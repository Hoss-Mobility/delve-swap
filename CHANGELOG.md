# Changelog

All notable changes to DelveSwap are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the plugin uses
[semver](https://semver.org/).

## [Unreleased]

## [1.1.1] — 2026-11-02

### Changed

- Compatibility cap raised to GoLand 2026.3 (`pluginUntilBuild = 263.*`);
  verified against 261, 262 and the 2026.3 RC (263.7307.110).
- Build toolchain updated: Gradle 9.3.0 → 9.8.2, Kotlin 2.4.21,
  IntelliJ Platform Gradle Plugin 2.19.0, JUnit 6.1.3, IntelliJ Plugin
  Verifier 1.411. `publishPlugin` now sets `archiveFiles` (the 2.19 API) so it
  keeps uploading the signed zip. No runtime behavior changes.
- CI: `actions/checkout@v7`, `actions/setup-java@v6`,
  `actions/upload-artifact@v7`, `gradle/actions` v5.0.2 (SHA-pinned).

### Removed

- Historical design docs under `docs/superpowers/` (described older class
  names; superseded by `.claude/CLAUDE.md`).

## [1.1.0] — 2026-06-03

### Changed

- Renamed all `Registry*` types to `DlvPath*` (`RegistryBackend` →
  `DlvPathBackend`, `RegistrySwapper` → `DlvPathSwapper`, `RegistrySwapListener`
  → `DlvPathSwapListener`, `GlobalRegistrySwapper` → `GlobalDlvPathSwapper`).
  The old names were misleading: `dlv.path` is a JVM system property, not an
  IntelliJ Registry key. Package renamed from `com.hoss.delveperconfig` to
  `com.hoss.delveswap` to match. The persisted XML serialization id is frozen at
  the legacy value for backward compatibility with v0.1.0 user configs.
- `DlvPathSwapper.baseline` now re-reads the backend whenever no sessions are
  active. Previously a snapshot was taken at IDE startup, so editing the
  IDE-wide `dlv.path` mid-session left the GLOBAL tier stuck on the old value.
- `DelvePathSettings` is now an immutable `data class`; the unused `copy()`
  method is gone (data-class `copy` replaces it).
- `processStartScheduled` logic is now a pure `resolveForLaunch(settings,
  baseline, expandedPath): Resolution` and a tiny listener that fires the
  resolved warning. Easier to unit-test, cleaner separation of concerns.
- `SentinelStore` is now a class with an injected path so tests don't touch the
  host IDE's system directory.

### Removed

- `PlatformRegistryBackend` — pure pass-through with no behavior. Callers use
  `SystemPropertyBackend` directly.
- `GoConfigSupport` — collapsed into the listener's inline
  `runProfile as? GoRunConfigurationBase<*>` check.

### Fixed

- `pluginUntilBuild` capped at `262.*`. Previously the plugin claimed
  compatibility with every future GoLand build, which would silently crash users
  on incompatible releases instead of disabling the plugin.

## [1.0.0] — 2026-05-28

### Added

- Per-run-configuration Delve binary path override now works for *Run on Target*
  (SSH) debug sessions, not only local debug. Re-applies the override in
  `processStarting` so the two-phase build+debug execution model of remote
  targets doesn't lose the value between phases.

### Changed

- Writes `dlv.path` as a JVM system property (what the Go plugin's
  `GoRunUtil.dlv()` actually reads) instead of an IntelliJ Registry value.

## [0.1.0]

### Added

- Initial release. Per-run-configuration Delve binary path override for Go
  Build and Go Test.
