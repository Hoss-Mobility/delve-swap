# DelveSwap — Code Review Fixes (Critical + Important)

Date: 2026-05-29
Status: Design approved, pending spec review

## Background

Three parallel code reviews of DelveSwap v1.0.0 (core execution/swap/recovery,
settings/UI/path, build/CI/tests/docs) surfaced no release-blocking build issues
but found real correctness hazards concentrated in the execution/listener layer,
which currently has near-zero test coverage. This spec covers the **Critical and
Important** findings. Minors and editor-side live validation are explicitly out
of scope.

All findings below were verified against source before writing this spec.

## Scope

In scope (6 fixes):

1. **C1** — `sessionKey` collision on `executionId == 0`.
2. **I3** — `processStarting` re-apply leaks `dlv.path` (bypasses ref count).
3. **I1** — per-project sentinel recovery can clobber a live session.
4. **I2** — restore writes `""` instead of clearing an absent original.
5. ~~**Deprecated** `addBrowseFolderListener(Project?, FileChooserDescriptor)`.~~
   **DROPPED** — see below; the finding was a misdiagnosis.
6. **Whitespace** path not trimmed → validator/runtime disagree.

Out of scope: all Minor findings; editor-side inline validation feedback; the
single-global-value "last-writer-wins" race (tracked separately as the
"per-session bridge" follow-up).

## Cross-cutting assumption

C1 and I3 rely on the **same `ExecutionEnvironment` instance** flowing through
`processStartScheduled` / `processStarting` / `processTerminated` /
`processNotStarted` for one execution. This must be confirmed during
implementation (platform behaviour; spot-check against bundled platform
sources). If it does not hold, the keying strategy needs revisiting.

## Fixes

### 1. C1 — sessionKey collision

`RegistrySwapListener.sessionKey(env)` currently returns `env.executionId.toString()`.
`ExecutionEnvironment.executionId` defaults to `0L` and is only assigned a unique
value on the standard `executeConfiguration`/`startRunProfile` path. Two
concurrent sessions whose id is `0` (e.g. the internally-created Run-on-Target
debug env) collide: the second `swap("0", …)` is dropped as a duplicate and the
first `restore("0")` releases the ref count for both, yanking a live session's
override.

**Fix:** extract a pure, testable helper:

```kotlin
internal fun sessionKey(executionId: Long, identity: Int): String =
    if (executionId != 0L) executionId.toString() else "id0:$identity"
```

The listener keeps its `private fun sessionKey(env)` as a thin delegate:
`sessionKey(env.executionId, System.identityHashCode(env))`. So existing call
sites (`processStartScheduled`, `processTerminated`, `processNotStarted`, and the
new I3 call in `processStarting`) are unchanged and all go through the pure helper.

Rationale: a non-zero `executionId` is platform-guaranteed unique, so prefer it.
When it is `0`, fall back to the env's identity hash, which is distinct for the
distinct env objects of concurrent runs. (A composite `id:name` key was rejected:
two same-named configs both at id `0` would still collide.)

### 2. I3 — processStarting leak

`processStarting` writes `System.setProperty("dlv.path", customPath)` directly,
outside the ref-counted `RegistrySwapper`. For Run-on-Target the build phase's
`processTerminated` restores/clears `dlv.path` before the real debug reads it,
so the re-apply is needed — but because it bypasses the swapper, nothing restores
it when the debug ends. Worse: the leaked value is later captured as the next
session's "original" and written to the sentinel.

**Fix:** route the re-apply through the swapper:

```kotlin
override fun processStarting(executorId: String, env: ExecutionEnvironment) {
    val customPath = env.getUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY)
    if (!customPath.isNullOrEmpty()) {
        swapper.swap(sessionKey(env), customPath)
    }
}
```

`swap` is idempotent for a known session key, so this is a no-op if the env
already swapped in `processStartScheduled`; otherwise it captures/sets correctly
and is balanced by the existing `processTerminated → restore`. Correct under both
orderings (build phase already terminated, or still overlapping). Requires the
C1 fix so the debug env gets a distinct key.

### 3. I1 — sentinel recovery clobbers a live session

`SentinelRecovery` is a `ProjectActivity` (runs once per opened project) using a
single global sentinel file. If a second project opens while another project has
a live swapped session, recovery reads the sentinel (the original) and writes it
over the currently-swapped value, yanking the live session's override.

**Fix:** add two guards plus a testable decision function.

- Add to `RegistrySwapper`:

  ```kotlin
  fun hasActiveSessions(): Boolean = synchronized(lock) { active.isNotEmpty() }
  ```

- Extract the pure decision:

  ```kotlin
  internal sealed interface RecoveryAction {
      data object None : RecoveryAction          // nothing to recover
      data object Clear : RecoveryAction         // original was absent -> clear
      data class Restore(val value: String) : RecoveryAction
      data object Discard : RecoveryAction        // invalid/garbage sentinel
  }

  internal fun recoveryAction(recovered: String?): RecoveryAction = when {
      recovered == null -> RecoveryAction.None
      recovered.isBlank() -> RecoveryAction.Clear
      DelvePathValidator.validateExpanded(recovered, overrideEnabled = true)
          .severity == DelvePathValidator.Severity.ERROR -> RecoveryAction.Discard
      else -> RecoveryAction.Restore(recovered)
  }
  ```

- `execute(project)` becomes:

  ```kotlin
  if (!firstRun.compareAndSet(false, true)) return            // once per process
  if (service<GlobalRegistrySwapper>().swapper.hasActiveSessions()) return
  val backend = PlatformRegistryBackend()
  when (val action = recoveryAction(SentinelStore.readAndClear())) {
      RecoveryAction.None -> Unit
      RecoveryAction.Clear -> backend.set("")
      RecoveryAction.Discard -> log.warn("Sentinel contained an invalid dlv.path; discarding.")
      is RecoveryAction.Restore -> { log.warn("Recovered leaked dlv.path; restoring."); backend.set(action.value) }
  }
  ```

  `firstRun` is a companion `AtomicBoolean`. Order matters: run-once guard first,
  then active-session guard (skip without consuming the sentinel — a live
  session's own `restore` will clear it).

### 4. I2 — restore writes "" instead of clearing

When the global `dlv.path` was unset at capture, `SystemPropertyBackend.get()`
returns `""`, so restore does `setProperty("dlv.path","")` — leaving a stale
empty value rather than an absent one.

**Fix (minimal, no interface change):**

```kotlin
override fun set(v: String) {
    if (v.isEmpty()) System.clearProperty(DLV_PATH_KEY)
    else System.setProperty(DLV_PATH_KEY, v)
}
```

`swap` only ever sets validated, non-blank paths, so an empty value reaches
`set` only on restore/recovery of an absent original — clearing is exactly right
and matches the Go plugin's documented "empty == null" semantics. The blank
sentinel case is handled by `recoveryAction` returning `Clear` (see I1), avoiding
the validator's `ERROR_EMPTY`.

(Making `get()` return `String?` and adding `clear()` to `RegistryBackend` was
rejected as unnecessary churn through backend/swapper/sentinel.)

### 5. Deprecated addBrowseFolderListener — DROPPED (misdiagnosis)

The original review claimed `addBrowseFolderListener(Project?, FileChooserDescriptor)`
(the 2-arg overload the code calls with `null`) was deprecated-for-removal. During
implementation, javap inspection of the bundled platform on **both** GoLand 2026.1
(261) and 262 showed that **2-arg overload is NOT deprecated** — only the unused
**4-arg** `(String, String, Project, FileChooserDescriptor)` variant carries
`@Deprecated(forRemoval=true)`. A migration to
`addBrowseFolderListener(TextBrowseFolderListener(descriptor))` was implemented and
verified Compatible on 261/262, then **reverted** by decision: the finding did not
correspond to a real problem, so per "every change must trace to a real finding,"
no change ships for #5.

### 6. Whitespace path

`" /opt/dlv "` is not blank, so it passes the `isBlank()` gate but fails
`Files.exists` → spurious `ERROR_NOT_FOUND`; the editor and runtime disagree.

**Fix (both locations, per approval):**

- `applyEditorTo`: `s.path = pathField.text.trim()` — normalize at input so
  stored XML is clean going forward.
- `PathExpansion.expand`: trim the raw input at the start so pre-existing or
  programmatically-set configs are also normalized at the single runtime
  chokepoint before validation and swap.

## Test plan

All fast unit tests, matching the existing style (no IDE fixture). TDD: write
the failing test first for each fix.

- **C1:** `sessionKey(executionId, identity)` — `id != 0` returns the id;
  `id == 0` with two different identities returns two different keys.
- **I3:** `RegistrySwapper` two-phase sequence — `swap(k1)`, `restore(k1)`,
  `swap(k2)`, `restore(k2)` returns the backend to the original value (no leak);
  assert the override is visible between `swap(k2)` and `restore(k2)`.
- **I1:** `RegistrySwapper.hasActiveSessions()` reflects swap/restore state;
  `recoveryAction(null) == None`, `recoveryAction("   ") == Clear`,
  `recoveryAction(validPath) == Restore(validPath)`,
  `recoveryAction(nonexistentPath) == Discard`.
- **I2:** `SystemPropertyBackend.set("")` clears the property (new case in
  `SystemPropertyBackendTest`); tighten the existing
  `swap via SystemPropertyBackend …` assertion from `isNullOrEmpty()` to
  `assertNull`. Add a `RegistrySwapper` restore-of-absent-original test using a
  test backend that models empty→cleared.
- **#6:** `PathExpansion.expand` (or `expandEnv`) strips surrounding whitespace
  from the raw path.

`TestRegistryBackend` (in `RegistrySwapListenerTest.kt`) may need a variant that
models the empty→cleared semantics for the I2 swapper test; keep the existing
one for the value-preserving tests.

## Verification

- `./gradlew test` — all new and existing tests pass.
- `./gradlew verifyPlugin` — no new compatibility problems.

## Files touched

- `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`
  (sessionKey helper, processStarting routing, `SystemPropertyBackend.set`,
  `RegistrySwapper.hasActiveSessions`).
- `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt`
  (guards + `recoveryAction`).
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt`
  (trim in `applyEditorTo`; #5 reverted).
- `src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt` (trim).
- `src/test/kotlin/com/hoss/delveperconfig/*` (new + updated tests).
