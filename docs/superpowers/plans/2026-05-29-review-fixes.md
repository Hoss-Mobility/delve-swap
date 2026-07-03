# DelveSwap Review-Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Critical + Important findings from the v1.0.0 code review: session-key collision, the `processStarting` `dlv.path` leak, sentinel-recovery clobbering, restore-empty-clears, and untrimmed whitespace paths. (A sixth finding — a "deprecated" browse-folder API — was investigated and dropped as a misdiagnosis; see Task 5.)

**Architecture:** All changes are local to four existing Kotlin files in `com.hoss.delveperconfig`. The strategy is to extract small pure helpers (`sessionKeyFor`, `reapplyOnStarting`, `recoveryAction`) so the listener/recovery logic becomes unit-testable without an IntelliJ test fixture, then wire the existing classes to those helpers. Tests stay in the current fast JUnit-5 unit-test style (no `BasePlatformTestCase`).

**Tech Stack:** Kotlin, IntelliJ Platform Gradle plugin 2.x (target GoLand 2026.1 / build 261), JUnit 5 (`org.junit.jupiter`). Build/test with the system **`gradle`** (9.4.1) — this repo has **no** Gradle wrapper (`./gradlew` is absent), so all commands below use `gradle`.

**Spec:** `docs/superpowers/specs/2026-05-29-review-fixes-design.md`

**Commits:** The repo owner performs all git commits. Each task ends at a **Checkpoint** — stop there for the owner to review and commit. Do **not** run `git add`/`git commit`/`git push`.

---

## File Structure

- `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` — add `sessionKeyFor`, `reapplyOnStarting`, `RegistrySwapper.hasActiveSessions`; change `SystemPropertyBackend.set`; rewrite `processStarting`; delegate `sessionKey(env)`.
- `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt` — add `RecoveryAction` + `recoveryAction`; add run-once + active-session guards in `execute`.
- `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt` — trim the path in `applyEditorTo` (Task 6). (Task 5's browse-folder migration was reverted — see Task 5.)
- `src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt` — trim the template in `expandEnv` (the runtime chokepoint that is also unit-testable).
- `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt` — add tests for `sessionKeyFor`, `reapplyOnStarting`, `hasActiveSessions`; tighten the existing restore-of-absent assertion.
- `src/test/kotlin/com/hoss/delveperconfig/SystemPropertyBackendTest.kt` — add empty-clears test.
- `src/test/kotlin/com/hoss/delveperconfig/SentinelRecoveryTest.kt` — **new**: `recoveryAction` cases.
- `src/test/kotlin/com/hoss/delveperconfig/PathExpansionTest.kt` — add whitespace-trim test.

**Note on test deviation from the spec:** the spec suggested a `TestRegistryBackend` variant that models empty→cleared for the I2 swapper test. The existing `swap via SystemPropertyBackend …` test already exercises restore-of-absent against the real backend, so Task 3 tightens that test instead of adding a redundant fake. The spec also said "trim at the start of `expand`"; Task 6 puts the trim in `expandEnv` (which `expand` always calls last) because `expand` needs a `Project` and is not unit-testable, while `expandEnv` is pure — same runtime guarantee, plus a real unit test.

---

## Task 1: C1 — collision-free session key

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt:183-184`
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `RegistrySwapListenerTest.kt` (and add the imports `import org.junit.jupiter.api.Assertions.assertNotEquals`):

```kotlin
@Test
fun `sessionKeyFor uses executionId when nonzero`() {
    assertEquals("42", sessionKeyFor(42L, 999))
}

@Test
fun `sessionKeyFor falls back to identity when executionId is zero`() {
    assertEquals("id0:111", sessionKeyFor(0L, 111))
    // two zero-id sessions with distinct env identities must never collide
    assertNotEquals(sessionKeyFor(0L, 111), sessionKeyFor(0L, 222))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `gradle test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: compile failure — `sessionKeyFor` is unresolved.

- [ ] **Step 3: Add the pure helper and delegate the member**

Append to the end of `RegistrySwapListener.kt` (top-level, same package):

```kotlin
/**
 * Pure session-key derivation, extracted for testability. `executionId` is unique when the
 * platform assigned it, but defaults to 0L for launches that never went through
 * `assignNewExecutionId()` (e.g. the internally-created Run-on-Target debug env). When it is
 * 0 we fall back to the env's identity hash, which is distinct for the distinct env objects
 * of concurrent runs — so two id-0 sessions never share a key.
 */
internal fun sessionKeyFor(executionId: Long, identity: Int): String =
    if (executionId != 0L) executionId.toString() else "id0:$identity"
```

Replace the existing member at `RegistrySwapListener.kt:183-184`:

```kotlin
    private fun sessionKey(env: ExecutionEnvironment): String =
        env.executionId.toString()
```

with:

```kotlin
    private fun sessionKey(env: ExecutionEnvironment): String =
        sessionKeyFor(env.executionId, System.identityHashCode(env))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS (all existing tests plus the two new ones).

- [ ] **Step 5: Checkpoint** — stop for the owner to review and commit.

---

## Task 2: I3 — route `processStarting` re-apply through the swapper

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt:155-168`
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `RegistrySwapListenerTest.kt`:

```kotlin
@Test
fun `reapplyOnStarting routes through swapper and is balanced by restore`() {
    val backend = TestRegistryBackend("/usr/bin/dlv")
    val swapper = RegistrySwapper(backend)

    // Run-on-Target: the build sub-execution swaps then terminates, clearing back to original.
    swapper.swap("build", "/opt/dlv-arm")
    swapper.restore("build")
    assertEquals("/usr/bin/dlv", backend.get())

    // The real debug re-applies the override at process-start via the seam...
    reapplyOnStarting(swapper, "debug", "/opt/dlv-arm")
    assertEquals("/opt/dlv-arm", backend.get())

    // ...and its own processTerminated restores it — no leak past the session.
    swapper.restore("debug")
    assertEquals("/usr/bin/dlv", backend.get())
}

@Test
fun `reapplyOnStarting is a no-op for null or empty path`() {
    val backend = TestRegistryBackend("/usr/bin/dlv")
    val swapper = RegistrySwapper(backend)
    reapplyOnStarting(swapper, "x", null)
    reapplyOnStarting(swapper, "y", "")
    assertEquals("/usr/bin/dlv", backend.get())  // nothing swapped
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `gradle test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: compile failure — `reapplyOnStarting` is unresolved.

- [ ] **Step 3: Add the seam and rewrite `processStarting`**

Append to the end of `RegistrySwapListener.kt`:

```kotlin
/**
 * Re-apply an override at process-start through the ref-counted swapper, so it is always
 * balanced by the matching `processTerminated -> restore`. Idempotent: a no-op if this env
 * already swapped in `processStartScheduled`.
 */
internal fun reapplyOnStarting(swapper: RegistrySwapper, sessionKey: String, customPath: String?) {
    if (!customPath.isNullOrEmpty()) swapper.swap(sessionKey, customPath)
}
```

Replace the existing `processStarting` (`RegistrySwapListener.kt:155-168`):

```kotlin
    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        // A "Run on target" debug fires two ExecutionEnvironments back-to-back: a fast build
        // sub-execution that terminates in ~200ms, then the real debug. Our processTerminated
        // for the first runs restore() and clears dlv.path before the real debug reads it, so
        // GoRunUtil.dlv() falls back to the bundled (x86_64) binary. The Go plugin copies our
        // CUSTOM_DELVE_PATH_KEY onto the second env's UserData, so we always re-apply
        // System.property from that key — the env's UserData is authoritative for *this*
        // run, regardless of any leftover JVM property from a previous run.
        val customPath = env.getUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY)
        if (!customPath.isNullOrEmpty() && System.getProperty("dlv.path") != customPath) {
            System.setProperty("dlv.path", customPath)
            log.info("Re-applied dlv.path=$customPath from env UserData")
        }
    }
```

with:

```kotlin
    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        // A "Run on target" debug fires two ExecutionEnvironments back-to-back: a fast build
        // sub-execution that terminates in ~200ms, then the real debug. The build run's
        // processTerminated restores dlv.path before the real debug reads it. The Go plugin
        // copies our CUSTOM_DELVE_PATH_KEY onto the second env, so we re-apply the override
        // here — but through the ref-counted swapper, so it is balanced by *this* env's
        // processTerminated -> restore and never leaks past the session.
        val customPath = env.getUserData(com.goide.dlv.DlvDebugProcess.CUSTOM_DELVE_PATH_KEY)
        reapplyOnStarting(swapper, sessionKey(env), customPath)
        if (!customPath.isNullOrEmpty()) log.info("Re-applied dlv.path=$customPath from env UserData")
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle test --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS.

- [ ] **Step 5: Checkpoint** — stop for the owner to review and commit.

---

## Task 3: I2 — restore clears an absent original instead of writing ""

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt:19-24` (`SystemPropertyBackend.set`)
- Test: `src/test/kotlin/com/hoss/delveperconfig/SystemPropertyBackendTest.kt`
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt:94-99` (tighten existing)

- [ ] **Step 1: Write the failing test + tighten the existing one**

Add to `SystemPropertyBackendTest.kt` (and add `import org.junit.jupiter.api.Assertions.assertNull`):

```kotlin
@Test
fun `set with empty string clears the property`() {
    System.setProperty("dlv.path", "/opt/dlv-armv7")
    SystemPropertyBackend().set("")
    assertNull(System.getProperty("dlv.path"))
}
```

In `RegistrySwapListenerTest.kt`, replace the body of `swap via SystemPropertyBackend exposes new dlv path to System getProperty` from `// Original was unset; backend wrote it as "" (empty string).` through the `assertTrue(...)` block (`:94-99`) with:

```kotlin
            swapper.restore("exec-1")
            // Original was unset, so restore clears the property (set("") -> clearProperty).
            assertNull(System.getProperty("dlv.path"))
```

Add `import org.junit.jupiter.api.Assertions.assertNull` to `RegistrySwapListenerTest.kt`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `gradle test --tests "com.hoss.delveperconfig.SystemPropertyBackendTest" --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: FAIL — `set with empty string clears the property` gets `""` not `null`; the tightened test gets `""` not `null`.

- [ ] **Step 3: Make `set` clear on empty**

Replace `SystemPropertyBackend` (`RegistrySwapListener.kt:19-24`):

```kotlin
internal class SystemPropertyBackend : RegistryBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()
    override fun set(v: String) { System.setProperty(DLV_PATH_KEY, v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}
```

with:

```kotlin
internal class SystemPropertyBackend : RegistryBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()

    // swap() only ever sets a validated, non-blank path, so an empty value reaches set() only
    // when restoring/recovering an original that was absent — clear it rather than leaving a
    // stale empty string (the Go plugin treats empty and absent equivalently).
    override fun set(v: String) {
        if (v.isEmpty()) System.clearProperty(DLV_PATH_KEY) else System.setProperty(DLV_PATH_KEY, v)
    }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle test --tests "com.hoss.delveperconfig.SystemPropertyBackendTest" --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS.

- [ ] **Step 5: Checkpoint** — stop for the owner to review and commit.

---

## Task 4: I1 — sentinel recovery cannot clobber a live session

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` (`RegistrySwapper` — add `hasActiveSessions`)
- Modify: `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt` (full rewrite)
- Test: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt` (`hasActiveSessions`)
- Test: `src/test/kotlin/com/hoss/delveperconfig/SentinelRecoveryTest.kt` (**new**)

- [ ] **Step 1: Write the failing tests**

Add to `RegistrySwapListenerTest.kt` (and add `import org.junit.jupiter.api.Assertions.assertFalse` and `import org.junit.jupiter.api.Assertions.assertTrue` if not present — `assertTrue` is already imported):

```kotlin
@Test
fun `hasActiveSessions reflects swap and restore`() {
    val swapper = RegistrySwapper(TestRegistryBackend("/usr/bin/dlv"))
    assertFalse(swapper.hasActiveSessions())
    swapper.swap("a", "/opt/dlv-a")
    assertTrue(swapper.hasActiveSessions())
    swapper.restore("a")
    assertFalse(swapper.hasActiveSessions())
}
```

Create `src/test/kotlin/com/hoss/delveperconfig/SentinelRecoveryTest.kt`:

```kotlin
package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SentinelRecoveryTest {

    @Test
    fun `null sentinel yields None`() {
        assertEquals(RecoveryAction.None, recoveryAction(null))
    }

    @Test
    fun `blank sentinel yields Clear`() {
        assertEquals(RecoveryAction.Clear, recoveryAction("   "))
    }

    @Test
    fun `nonexistent path yields Discard`() {
        assertEquals(RecoveryAction.Discard, recoveryAction("/no/such/dlv-binary-xyz"))
    }

    @Test
    fun `valid executable path yields Restore`(@TempDir dir: Path) {
        val exe = dir.resolve("dlv")
        Files.writeString(exe, "#!/bin/sh\n")
        exe.toFile().setExecutable(true)
        assertEquals(RecoveryAction.Restore(exe.toString()), recoveryAction(exe.toString()))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `gradle test --tests "com.hoss.delveperconfig.SentinelRecoveryTest" --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: compile failure — `hasActiveSessions`, `RecoveryAction`, and `recoveryAction` are unresolved.

- [ ] **Step 3a: Add `hasActiveSessions` to `RegistrySwapper`**

Inside `class RegistrySwapper` in `RegistrySwapListener.kt`, after the `restore` function, add:

```kotlin
    fun hasActiveSessions(): Boolean = synchronized(lock) { active.isNotEmpty() }
```

- [ ] **Step 3b: Rewrite `SentinelRecovery.kt`**

Replace the entire contents of `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt` with:

```kotlin
package com.hoss.delveperconfig

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/** What to do with the value read from the sentinel file. Pure, so it is unit-testable. */
internal sealed interface RecoveryAction {
    data object None : RecoveryAction          // no sentinel -> nothing to recover
    data object Clear : RecoveryAction          // original was absent -> clear dlv.path
    data class Restore(val value: String) : RecoveryAction
    data object Discard : RecoveryAction         // invalid/garbage sentinel -> leave dlv.path
}

internal fun recoveryAction(recovered: String?): RecoveryAction = when {
    recovered == null -> RecoveryAction.None
    recovered.isBlank() -> RecoveryAction.Clear
    DelvePathValidator.validateExpanded(recovered, overrideEnabled = true)
        .severity == DelvePathValidator.Severity.ERROR -> RecoveryAction.Discard
    else -> RecoveryAction.Restore(recovered)
}

/**
 * If the previous IDE session crashed mid-debug, the sentinel file holds the original
 * `dlv.path` captured before the swap; we validate and restore it on next start.
 *
 * Two guards prevent this per-project activity from corrupting a *running* session:
 *  - run-once per IDE process ([firstRun]) — the EP fires once per opened project, but
 *    crash recovery is a per-process startup concern;
 *  - skip while any swap is active (`hasActiveSessions`) — a live session legitimately owns
 *    `dlv.path` and writes its own sentinel, which its own `restore()` clears. The sentinel
 *    we'd see then is not a crash artifact.
 *
 * A blank sentinel means the original was absent, so we clear rather than run it through the
 * path validator (which would report ERROR_EMPTY and wrongly discard).
 */
class SentinelRecovery : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!firstRun.compareAndSet(false, true)) return
        if (service<GlobalRegistrySwapper>().swapper.hasActiveSessions()) return
        val backend = PlatformRegistryBackend()
        when (val action = recoveryAction(SentinelStore.readAndClear())) {
            RecoveryAction.None -> Unit
            RecoveryAction.Clear -> backend.set("")
            RecoveryAction.Discard ->
                thisLogger().warn("Sentinel contained an invalid dlv.path; discarding.")
            is RecoveryAction.Restore -> {
                thisLogger().warn("Recovered leaked dlv.path from sentinel; restoring previous value.")
                backend.set(action.value)
            }
        }
    }

    private companion object {
        val firstRun = AtomicBoolean(false)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle test --tests "com.hoss.delveperconfig.SentinelRecoveryTest" --tests "com.hoss.delveperconfig.RegistrySwapListenerTest"`
Expected: PASS.

- [ ] **Step 5: Checkpoint** — stop for the owner to review and commit.

---

## Task 5: ~~Migrate off the deprecated `addBrowseFolderListener` overload~~ — DROPPED

**Status: dropped (misdiagnosis).** The original review flagged
`addBrowseFolderListener(Project?, FileChooserDescriptor)` as deprecated-for-removal.
During execution, javap inspection of the bundled platform on both GoLand 2026.1 (261)
and 262 showed the **2-arg overload the code uses is NOT deprecated** — only the unused
**4-arg** `(String, String, Project, FileChooserDescriptor)` variant carries
`@Deprecated(forRemoval=true)`. A migration to
`addBrowseFolderListener(TextBrowseFolderListener(descriptor))` was implemented and
verified Compatible on 261/262, then **reverted** by owner decision because it did not
fix a real problem. `DelvePathSettingsEditor.kt` keeps its original browse-listener call
(it still receives the whitespace-trim from Task 6).

---

## Task 6: Trim surrounding whitespace from override paths

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/PathExpansion.kt:10-14` (`expandEnv`)
- Modify: `src/main/kotlin/com/hoss/delveperconfig/DelvePathSettingsEditor.kt:47` (`applyEditorTo`)
- Test: `src/test/kotlin/com/hoss/delveperconfig/PathExpansionTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `PathExpansionTest.kt`:

```kotlin
@Test
fun `surrounding whitespace is trimmed before expansion`() {
    val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
    assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("  \$ARM_DLV/bin  ", env))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle test --tests "com.hoss.delveperconfig.PathExpansionTest"`
Expected: FAIL — result is `"  /opt/dlv-arm/bin  "` (untrimmed), not `"/opt/dlv-arm/bin"`.

- [ ] **Step 3: Trim the template in `expandEnv`**

Replace `expandEnv` (`PathExpansion.kt:10-14`):

```kotlin
    /** Pure function — expands $VAR and ${VAR} against the provided map. Used by tests and runtime. */
    fun expandEnv(input: String, env: Map<String, String>): String =
        ENV_PATTERN.replace(input) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            env[name] ?: match.value
        }
```

with (trim the template first, so leading/trailing whitespace the user typed never reaches the filesystem check; `expand` calls this last, so the runtime path is covered too):

```kotlin
    /** Pure function — trims, then expands $VAR and ${VAR} against the provided map. Used by tests and runtime. */
    fun expandEnv(input: String, env: Map<String, String>): String =
        ENV_PATTERN.replace(input.trim()) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            env[name] ?: match.value
        }
```

- [ ] **Step 4: Trim at the editor input boundary**

Replace `DelvePathSettingsEditor.kt:47`:

```kotlin
        s.path = pathField.text
```

with:

```kotlin
        s.path = pathField.text.trim()
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `gradle test --tests "com.hoss.delveperconfig.PathExpansionTest"`
Expected: PASS (new test plus the four existing ones — `empty input returns empty` still passes: `"".trim()` is `""`).

- [ ] **Step 6: Checkpoint** — stop for the owner to review and commit.

---

## Final verification

- [ ] **Run the full suite:** `gradle test`
  Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Run the verifier:** `gradle verifyPlugin`
  Expected: BUILD SUCCESSFUL, no new compatibility problems.
- [ ] **Owner commits** the completed work.

---

## Self-review notes

- **Spec coverage:** C1 → Task 1; I3 → Task 2; I2 → Task 3; I1 → Task 4; whitespace → Task 6. (Task 5, the "deprecated listener" finding, was dropped as a misdiagnosis.)
- **Type consistency:** `sessionKeyFor(Long, Int)`, `reapplyOnStarting(RegistrySwapper, String, String?)`, `RegistrySwapper.hasActiveSessions(): Boolean`, `RecoveryAction` (`None`/`Clear`/`Restore(value)`/`Discard`), `recoveryAction(String?): RecoveryAction` — used consistently across tasks and tests.
- **Deviations from spec** (documented above): no redundant clear-modelling `TestRegistryBackend` (Task 3 tightens the real-backend test); the whitespace trim lives in `expandEnv` rather than `expand` (testable, same runtime guarantee).
- **Out of scope (unchanged):** all Minor findings, editor-side live validation, and the single-global-value last-writer-wins race.
