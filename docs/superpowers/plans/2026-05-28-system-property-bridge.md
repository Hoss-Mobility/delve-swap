# dlv.path System-Property Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the per-config `dlv.path` override actually affect the binary uploaded to a remote SSH target.

**Architecture:** The Go plugin's `com.goide.execution.GoRunUtil.dlv(TargetEnvironmentRequest)` reads `System.getProperty("dlv.path")` — not the IntelliJ `Registry` value — when deciding which dlv to upload. Today our `PlatformRegistryBackend` writes only to `Registry`, so the Go plugin never sees our swap on the Run-on-Target path. Fix: split into `SystemPropertyBackend` + `IntelliJRegistryBackend` and make `PlatformRegistryBackend` write through to both. Read-back prefers System.property since that's what the Go plugin consults.

**Tech Stack:** Kotlin 2.2, IntelliJ Platform 2.16, JUnit Jupiter 5.11, IntelliJ Platform Gradle Plugin 2.x.

**Bytecode evidence:** `com.goide.execution.GoRunUtil.dlv(...)` constant `#598 = String dlv.path` is read via `java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;`, not `com/intellij/openapi/util/registry/Registry`. The Registry → System property bridge runs once during settings load, so runtime Registry writes don't propagate. Returning a file path verbatim short-circuits the bundled-per-arch selector, so any valid file (including armv7) will be uploaded as-is.

---

## File Structure

- **Modify:** `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt`
  - Add `SystemPropertyBackend` and `IntelliJRegistryBackend` (internal classes).
  - Rewrite `PlatformRegistryBackend` to delegate to both.
  - Update the docstring on `RegistrySwapper` (the "Go plugin reads dlv.path synchronously inside swap()" sentence is incomplete — explain *which* store the Go plugin actually reads).
- **Modify:** `src/main/kotlin/com/hoss/delveperconfig/SentinelRecovery.kt`
  - No structural change; `PlatformRegistryBackend()` continues to be the recovery write path. Behavior changes implicitly because the new backend writes to System.property.
- **Create:** `src/test/kotlin/com/hoss/delveperconfig/SystemPropertyBackendTest.kt`
  - Pure-Java unit tests on `SystemPropertyBackend` (no IntelliJ test fixture needed).
- **Modify:** `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`
  - Add one test using `SystemPropertyBackend` directly with `RegistrySwapper` to verify the end-to-end behavior at the unit level.

No new files in production beyond the internal classes; no public API change.

---

### Task 1: Failing test — `SystemPropertyBackend` writes/reads `dlv.path` system property

**Files:**
- Create: `src/test/kotlin/com/hoss/delveperconfig/SystemPropertyBackendTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hoss.delveperconfig

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SystemPropertyBackendTest {

    private var savedSystemValue: String? = null

    @BeforeEach
    fun captureSystemProperty() {
        savedSystemValue = System.getProperty("dlv.path")
        System.clearProperty("dlv.path")
    }

    @AfterEach
    fun restoreSystemProperty() {
        if (savedSystemValue == null) {
            System.clearProperty("dlv.path")
        } else {
            System.setProperty("dlv.path", savedSystemValue)
        }
    }

    @Test
    fun `set writes System dlv-path property`() {
        SystemPropertyBackend().set("/opt/dlv-armv7")
        assertEquals("/opt/dlv-armv7", System.getProperty("dlv.path"))
    }

    @Test
    fun `get reads System dlv-path property`() {
        System.setProperty("dlv.path", "/opt/dlv-armv7")
        assertEquals("/opt/dlv-armv7", SystemPropertyBackend().get())
    }

    @Test
    fun `get returns empty string when property is unset`() {
        System.clearProperty("dlv.path")
        assertEquals("", SystemPropertyBackend().get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.hoss.delveperconfig.SystemPropertyBackendTest -i`
Expected: FAIL with "unresolved reference: SystemPropertyBackend".

- [ ] **Step 3: Implement `SystemPropertyBackend`**

Modify `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt` — add this class right after the `RegistryBackend` interface, before `PlatformRegistryBackend`:

```kotlin
internal class SystemPropertyBackend : RegistryBackend {
    override fun get(): String = System.getProperty(DLV_PATH_KEY).orEmpty()
    override fun set(v: String) { System.setProperty(DLV_PATH_KEY, v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.hoss.delveperconfig.SystemPropertyBackendTest -i`
Expected: PASS, three tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt \
        src/test/kotlin/com/hoss/delveperconfig/SystemPropertyBackendTest.kt
git commit -m "feat: SystemPropertyBackend for dlv.path System property reads/writes"
```

---

### Task 2: Failing test — `PlatformRegistryBackend.set()` updates System property

**Files:**
- Modify: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `RegistrySwapListenerTest.kt`, before the closing `}` of the class:

```kotlin
    @Test
    fun `swap via SystemPropertyBackend exposes new dlv path to System getProperty`() {
        val savedSystem = System.getProperty("dlv.path")
        try {
            System.clearProperty("dlv.path")
            val swapper = RegistrySwapper(SystemPropertyBackend())

            swapper.swap("exec-1", "/opt/dlv-armv7")
            assertEquals("/opt/dlv-armv7", System.getProperty("dlv.path"))

            swapper.restore("exec-1")
            // Original was unset; backend wrote it as "" (empty string).
            // Acceptable: GoRunUtil treats empty and null equivalently (it null-checks then
            // length-checks). If this ever changes upstream, switch to clearProperty in restore.
            val after = System.getProperty("dlv.path")
            assertTrue(after.isNullOrEmpty(),
                "expected dlv.path to be cleared or empty after restore, got '$after'")
        } finally {
            if (savedSystem == null) System.clearProperty("dlv.path")
            else System.setProperty("dlv.path", savedSystem)
        }
    }
```

Also add to the imports at the top:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.hoss.delveperconfig.RegistrySwapListenerTest -i`
Expected: PASS (this one already works because `SystemPropertyBackend` exists from Task 1 and the swapper is generic). If FAIL: investigate; the swapper's `original` capture should still hold "" for unset.

Note: This task's "fail then pass" cycle is degenerate because the production class already works at this layer. We keep it as a regression guard.

- [ ] **Step 3: Commit (test-only)**

```bash
git add src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt
git commit -m "test: end-to-end swap via SystemPropertyBackend updates System property"
```

---

### Task 3: Failing test — `PlatformRegistryBackend.set()` writes BOTH Registry and System property

**Files:**
- Modify: `src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt`

- [ ] **Step 1: Write the failing test**

This test verifies the composite write. We can't directly assert on `Registry.get(...)` without an IDE fixture, so we test by injecting two observable child backends through a new constructor parameter on `PlatformRegistryBackend` (added in Task 4).

Append to `RegistrySwapListenerTest.kt`:

```kotlin
    @Test
    fun `composite backend writes through to both child backends`() {
        val systemSide = TestRegistryBackend("")
        val registrySide = TestRegistryBackend("")
        val backend = PlatformRegistryBackend(systemSide, registrySide)

        backend.set("/opt/dlv-armv7")
        assertEquals("/opt/dlv-armv7", systemSide.get())
        assertEquals("/opt/dlv-armv7", registrySide.get())
    }

    @Test
    fun `composite backend get prefers system side when non-empty`() {
        val systemSide = TestRegistryBackend("/from-system")
        val registrySide = TestRegistryBackend("/from-registry")
        val backend = PlatformRegistryBackend(systemSide, registrySide)

        assertEquals("/from-system", backend.get())
    }

    @Test
    fun `composite backend get falls back to registry when system side is empty`() {
        val systemSide = TestRegistryBackend("")
        val registrySide = TestRegistryBackend("/from-registry")
        val backend = PlatformRegistryBackend(systemSide, registrySide)

        assertEquals("/from-registry", backend.get())
    }
```

Move `TestRegistryBackend` from a private nested class to an `internal` top-level class within the test file (so the same instance can be used across tests):

```kotlin
internal class TestRegistryBackend(initial: String) : RegistryBackend {
    @Volatile private var value: String = initial
    override fun get(): String = value
    override fun set(v: String) { value = v }
}
```

Delete the private `class TestRegistryBackend` block at the bottom of the existing class body, and update every `TestRegistryBackend(...)` call site in the file — they remain unchanged because the name resolves to the new top-level class.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.hoss.delveperconfig.RegistrySwapListenerTest -i`
Expected: FAIL with "constructor PlatformRegistryBackend(TestRegistryBackend, TestRegistryBackend) not found" — the current `PlatformRegistryBackend` has no-arg constructor only.

- [ ] **Step 3: Commit (test-only, must be before Task 4 implementation)**

```bash
git add src/test/kotlin/com/hoss/delveperconfig/RegistrySwapListenerTest.kt
git commit -m "test: composite PlatformRegistryBackend writes both stores"
```

---

### Task 4: Implement composite `PlatformRegistryBackend`

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt:20-25`

- [ ] **Step 1: Replace the current `PlatformRegistryBackend`**

Find this block in `RegistrySwapListener.kt`:

```kotlin
class PlatformRegistryBackend : RegistryBackend {
    override fun get(): String = Registry.get(DLV_PATH_KEY).asString()
    override fun set(v: String) { Registry.get(DLV_PATH_KEY).setValue(v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}
```

Replace it with:

```kotlin
internal class IntelliJRegistryBackend : RegistryBackend {
    override fun get(): String = Registry.get(DLV_PATH_KEY).asString()
    override fun set(v: String) { Registry.get(DLV_PATH_KEY).setValue(v) }

    companion object { const val DLV_PATH_KEY = "dlv.path" }
}

/**
 * Writes through to BOTH the IntelliJ Registry value `dlv.path` and the
 * `dlv.path` Java System property. The Go plugin's GoRunUtil.dlv(...) reads
 * the System property (NOT the Registry) when picking the dlv binary to
 * upload to a remote target, so the System-property write is the load-bearing
 * one. The Registry write is kept for parity with the user-facing
 * Settings > Advanced view of `dlv.path`.
 *
 * Reads prefer the System-property side (matching what the Go plugin will
 * actually consult); the Registry side is a fallback for environments where
 * the bridge has populated only the Registry.
 */
class PlatformRegistryBackend(
    private val system: RegistryBackend = SystemPropertyBackend(),
    private val registry: RegistryBackend = IntelliJRegistryBackend(),
) : RegistryBackend {
    override fun get(): String = system.get().ifEmpty { registry.get() }
    override fun set(v: String) {
        registry.set(v)
        system.set(v)
    }
}
```

The `SystemPropertyBackend` class (already added in Task 1) is already present above; leave it.

- [ ] **Step 2: Run all tests**

Run: `./gradlew test -i`
Expected: All `RegistrySwapListenerTest` tests pass, including the three new composite ones. `SystemPropertyBackendTest` still passes. Other test files unaffected.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt
git commit -m "fix: write dlv.path to System property so Go plugin sees the swap

GoRunUtil.dlv() reads System.getProperty(\"dlv.path\"), not the Registry
value. Splitting PlatformRegistryBackend into a composite that writes
both keeps the Registry view in sync while making the override actually
reach the Go plugin's remote-target upload path."
```

---

### Task 5: Update `RegistrySwapper` docstring

**Files:**
- Modify: `src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt:27-35`

- [ ] **Step 1: Rewrite the docstring**

Replace the existing docstring block above `class RegistrySwapper(...)`:

```kotlin
/**
 * Captures the original `dlv.path` once on the first active session and restores it once
 * the last session ends. While multiple sessions are active, the backend value is the
 * most-recently-swapped value — the Go plugin reads `dlv.path` synchronously inside swap(),
 * so only the value at swap-time matters per session.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered
 * on next IDE startup.
 */
```

with:

```kotlin
/**
 * Captures the original `dlv.path` once on the first active session and restores it once
 * the last session ends. While multiple sessions are active, the backend value is the
 * most-recently-swapped value.
 *
 * Read semantics: the Go plugin's GoRunUtil.dlv(TargetEnvironmentRequest) reads
 * System.getProperty("dlv.path") inside GoRunningState.startProcess (not the IntelliJ
 * Registry), so the PlatformRegistryBackend writes both stores to ensure GoRunUtil sees
 * our swap. Because GoRunUtil reads the property fresh on each debug start, the value
 * present at startProcess-time is what the Go plugin uses for that session.
 *
 * Known limitation: if two debug sessions are scheduled within a few hundred milliseconds
 * of each other and target different per-config paths, last-writer-wins — both sessions
 * see the second swap's value. See [docs/superpowers/plans/...-race.md] for the proposed
 * follow-up.
 *
 * Writes a sentinel file holding the original so a crash mid-session can be recovered
 * on next IDE startup.
 */
```

- [ ] **Step 2: Verify build still compiles**

Run: `./gradlew compileKotlin -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/hoss/delveperconfig/RegistrySwapListener.kt
git commit -m "docs: clarify RegistrySwapper reads System property, not Registry"
```

---

### Task 6: Manual end-to-end verification on the actual ARM device

**Files:** none

This step cannot be automated by the unit-test suite — it exercises the actual GoLand Run-on-Target debug path. Treat it as a release gate.

- [ ] **Step 1: Build the plugin locally**

Run: `./gradlew buildPlugin`
Expected: `build/distributions/delveswap-<version>.zip` exists.

- [ ] **Step 2: Install the built plugin into the user's GoLand instance**

In GoLand: `Settings → Plugins → ⚙ (cog) → Install Plugin from Disk...` and select the .zip from step 1.
Restart GoLand when prompted.

- [ ] **Step 3: Reproduce the original failing scenario**

Open the gateway project. Open the `go-router` run configuration → Delve tab. Confirm:
- "Use global dlv.path" is **unchecked**.
- Path field points to `/home/skast/GolandProjects/gateway/go-router/infra/debugger/dlv-armv7` (or whatever armv7 dlv you currently have).

- [ ] **Step 4: Trigger Run-on-Target debug**

Click Debug for the `go-router` run config (Run on: your SSH target). Wait for the debug attempt to either start successfully or fail.

- [ ] **Step 5: SSH into the device and inspect the uploaded binary**

Run: `ssh hoss@192.168.50.68 'file /hoss/go-router/*/dlv | tail -5'`
Expected: `... ELF 32-bit LSB executable, ARM, EABI5 ...` (not `x86-64`).

- [ ] **Step 6: Confirm debug actually attaches**

In GoLand's debug console, you should NOT see `Syntax error: "(" unexpected`. The Delve session should start and breakpoints should hit.

- [ ] **Step 7: Restore: stop debug, confirm `dlv.path` is back to its pre-swap value**

After the debug session terminates, open `Help → Find Action → Registry...`, search for `dlv.path`. Expected: empty (or whatever it was before — for the user's setup, empty since idea.properties has no `dlv.path` line).

Also verify with `jps -v | grep -i goland` and inspect `-Ddlv.path` if relevant — but most reliable is: start a second debug WITHOUT a per-config override (set "Use global" on a fresh config) and confirm the bundled x86_64 dlv is uploaded again (i.e., we successfully restored to "unset" so the bundled selector runs).

- [ ] **Step 8: If step 5 still shows x86_64**

Capture and paste back:
- The full `Swapping dlv.path -> ...` block from `idea.log` for this run
- `System.getProperty("dlv.path")` via GoLand's Tools → IDE Internal Actions if available, or by adding a temporary `thisLogger().info("System.getProperty(dlv.path)=" + System.getProperty("dlv.path"))` to `RegistrySwapListener.processStartScheduled`
- `ssh hoss@192.168.50.68 'ls -la /hoss/go-router/*/'`

These three together definitively show whether (a) our System.property write happened, (b) it was clobbered by something between our write and `GoRunningState.startProcess`, or (c) GoRunUtil.dlv()'s short-circuit path requires a directory layout rather than a file.

- [ ] **Step 9: Commit a release tag once verified**

```bash
git tag -a v0.2.0 -m "fix: dlv.path System property bridge for Run-on-Target uploads"
git push origin v0.2.0
```

Do NOT push the tag until the user confirms step 5/6 pass on the real device.

---

## Self-Review

**Spec coverage:**
- "Write to System property in addition to Registry" → Tasks 1, 4 ✓
- "Read from System property first for capture" → Task 4 (`get()` prefers system side) ✓
- "Update SentinelRecovery" → No code change needed; SentinelRecovery uses `PlatformRegistryBackend()` which now writes both stores ✓ (verified by inspection — `SentinelRecovery.kt:24` is `PlatformRegistryBackend().set(recovered)`)
- "Update docstring on RegistrySwapper" → Task 5 ✓
- "Add test verifying System.property is set after swap()" → Tasks 1, 2, 3 ✓
- "Verify on real device" → Task 6 ✓

**Placeholder scan:** None found. Every step has either code or an exact command + expected output.

**Type consistency:**
- `SystemPropertyBackend` class name: consistent across Tasks 1, 3, 4.
- `IntelliJRegistryBackend` class name: introduced in Task 4 only, used internally only.
- `PlatformRegistryBackend(system, registry)` two-arg constructor: introduced in Task 4, used in Task 3 tests.
- `DLV_PATH_KEY = "dlv.path"`: defined twice (in `SystemPropertyBackend` and `IntelliJRegistryBackend`). Both refer to the same string. Mild duplication; not a bug. Could be hoisted to a file-level `const` in a cleanup PR.
- `TestRegistryBackend`: promoted from private nested to `internal` top-level inside the test file in Task 3. All existing call sites in that file remain unchanged because the name resolves identically.

**One known limitation not solved here:** the parallel-debug race (two `swap()`s 71ms apart with different per-config paths → last writer wins). This affects both the existing Registry-only design and the new composite. Out of scope for this plan; tracked as a follow-up in the updated docstring (Task 5).
