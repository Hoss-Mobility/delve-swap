# 3-Tier Delve Path Resolution — Design

**Date:** 2026-05-30
**Status:** Approved (design); pending implementation plan

## Problem

`dlv.path` is a single, process-global JVM system property read by the GoLand
Go plugin to choose the Delve binary. This plugin swaps it per debug session.

When two projects debug concurrently with *different* Delve binaries, a config
that wants the default delve silently inherits the other project's binary:

1. **Project A** — ARM debugger, custom `dlv.path` set in its run config.
2. **Project B** — x86 debugger, "Use global dlv.path" checked.
3. A starts first and swaps the global `dlv.path` → ARM binary. A's session
   keeps the property swapped until it ends.
4. B starts. The current listener does:
   ```kotlin
   if (s.useGlobal || s.path.isBlank()) return  // touches nothing
   ```
   So B does **not** reset `dlv.path`. It inherits A's leaked ARM binary and
   fails with a syntax/exec error.

Root cause: "Use global" today means *"leave whatever is currently set"*, not
*"use the real default."* Because A's value is still set, B leaks it.

## Goal

Give each run config an explicit, leak-proof choice of Delve binary, resolved
and asserted **per session** so one project's session can never bleed into
another's.

## Resolution model (3 tiers)

Each supported run config resolves to a **desired `dlv.path` value**, asserted
per session in `RegistrySwapListener.processStartScheduled`:

| Tier        | UI label              | Desired value asserted                                  |
|-------------|-----------------------|---------------------------------------------------------|
| **Bundled** | "GoLand bundled delve"| `""` (empty → Go plugin uses its shipped dlv)           |
| **Global**  | "Global dlv.path" (default) | the **IDE baseline**, captured once at startup    |
| **Custom**  | "Custom: …"           | the validated, expanded custom path                     |

The core fix is removing the early-return. **Every** supported config now
actively asserts its desired value through the ref-counted swapper. A "Global"
or "Bundled" config therefore resets `dlv.path` to the correct value rather than
inheriting whatever a concurrent session left behind. Project B (Global) asserts
the baseline → no ARM leak.

### Baseline capture

`GlobalRegistrySwapper` reads `System.getProperty("dlv.path")` **once at service
init** (≈ IDE startup, before any session has swapped) and stores it as an
immutable baseline. "Global" resolves to this baseline. The ref-counted swapper
also restores to it when all sessions drain.

If no IDE-wide `dlv.path` was ever configured, the baseline is empty, so
**Global and Bundled behave identically** (both = shipped dlv) — which is
correct.

## Components

### `DelvePathSettings` (persistence)

Replace the `useGlobal: Boolean` field with a 3-way enum:

```kotlin
enum class DelveSource { BUNDLED, GLOBAL, CUSTOM }
class DelvePathSettings(
    var source: DelveSource = DelveSource.GLOBAL,
    var path: String = "",
)
```

**Backward-compatible read** of existing persisted configs:

- legacy attribute `useGlobal="true"`  → `GLOBAL`
- legacy attribute `useGlobal="false"` → `CUSTOM`
- new configs persist a `source="BUNDLED|GLOBAL|CUSTOM"` attribute.
- Default state (`GLOBAL` + empty path) still writes **no element**, keeping the
  run-config `.xml` clean and diff-free for untouched configs.

### `DelvePathSettingsEditor` (UI)

Replace the single checkbox with a **3-way radio group**:

```
Delve binary:
  ( ) GoLand bundled delve
  (•) Global dlv.path        ← default
  ( ) Custom:  [______________] [Browse]

  Supports $PROJECT_DIR$, $USER_HOME$, and $VAR / ${VAR} env vars.
```

- Path field + hint label enabled **only** when **Custom** is selected.
- Default selection for a new config: **Global**.

### `RegistrySwapListener` (resolution + assertion)

- Remove `if (s.useGlobal || s.path.isBlank()) return`.
- Resolve desired value by `source`:
  - `CUSTOM`  → expand + validate the path (existing logic).
  - `GLOBAL`  → baseline.
  - `BUNDLED` → `""`.
- Assert the desired value through the existing ref-counted `RegistrySwapper`
  (`swap`/`restore`) and set `CUSTOM_DELVE_PATH_KEY` on the env as today.

### `RegistrySwapper` / `GlobalRegistrySwapper`

- Add an immutable `baseline` captured at construction from the backend.
- "Global" resolution and the drain-restore both target this baseline.

## Validation

- **Custom** → existing `DelvePathValidator` (ERROR if empty/missing, WARN if
  not executable). On ERROR: notify the user and fall back to baseline (do not
  swap), matching current behavior.
- **Global / Bundled** → no path validation; asserting the baseline or `""` is
  always safe.

## Error handling

- Custom path resolves to a missing file → WARNING notification, fall back to
  baseline; no swap performed for that session.
- Sentinel crash-recovery (`SentinelRecovery`) is unaffected: it still restores
  the captured original on next startup if a session crashed mid-swap.

## Testing

Unit tests (no live IDE required):

1. **Settings round-trip** including **legacy `useGlobal` migration** (both
   `true` and `false` paths) and the default-state "no element" rule.
2. **Source → desired-value resolution** for all three tiers (Custom expands +
   validates; Global → baseline; Bundled → `""`).
3. **Leak regression test** reproducing the A-then-B scenario: a CUSTOM session
   is active and has swapped to a custom value; a second GLOBAL session asserts
   the baseline (not the leaked custom value); on drain, the property returns to
   baseline.

## Verification items (confirm during implementation)

- `dlv.path=""` reliably selects GoLand's bundled dlv. (Code comments already
  assert empty ≡ absent ≡ bundled — confirm against the Go plugin.)
- Whether `CUSTOM_DELVE_PATH_KEY` is honored per-session for **local** debug as
  well as Run-on-Target. If it is, it further hardens against the start-race the
  current code documents as a known limitation (two starts racing before either
  `startProcess` reads the property).

## Out of scope (YAGNI)

- No new application-level settings page; tier 2 reuses the IDE baseline.
- No per-session bridge to fully eliminate the documented concurrent-start race
  (tracked separately); this design narrows but does not claim to remove it for
  local debug if `CUSTOM_DELVE_PATH_KEY` turns out not to be honored there.
