# Changelog

All notable changes to DelveSwap are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the plugin uses
[semver](https://semver.org/).

## [Unreleased]

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
