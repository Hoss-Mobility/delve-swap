package com.hoss.delveswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DlvPathSwapListenerTest {

    @Test
    fun `swap-restore on a single execution returns to original value`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)

        val sessionId = "exec-1"
        swapper.swap(sessionId, "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm", backend.get())

        swapper.restore(sessionId)
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `overlapping sessions both see their swap and original restored only after last release`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)

        swapper.swap("a", "/opt/dlv-a")
        assertEquals("/opt/dlv-a", backend.get())
        swapper.swap("b", "/opt/dlv-b")
        assertEquals("/opt/dlv-b", backend.get())

        swapper.restore("a")
        assertEquals("/opt/dlv-b", backend.get())

        swapper.restore("b")
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `restore is idempotent and safe for unknown session ids`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)
        swapper.restore("never-swapped")  // must not throw
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `concurrent swaps from two threads do not corrupt the original value`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        repeat(50) { i ->
            pool.submit {
                start.await()
                swapper.swap("s$i", "/opt/dlv-$i")
                swapper.restore("s$i")
            }
        }
        start.countDown()
        pool.shutdown()
        check(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `duplicate swap for same session id is a no-op`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)

        swapper.swap("a", "/opt/dlv-a")
        swapper.swap("a", "/opt/dlv-IGNORED")
        assertEquals("/opt/dlv-a", backend.get())   // second swap with same id is no-op

        swapper.restore("a")
        assertEquals("/usr/bin/dlv", backend.get()) // single restore returns to original
    }

    @Test
    fun `swap via SystemPropertyBackend exposes new dlv path to System getProperty`() {
        val savedSystem = System.getProperty("dlv.path")
        try {
            System.clearProperty("dlv.path")
            val swapper = swapper(SystemPropertyBackend())

            swapper.swap("exec-1", "/opt/dlv-armv7")
            assertEquals("/opt/dlv-armv7", System.getProperty("dlv.path"))

            swapper.restore("exec-1")
            // Original was unset, so restore clears the property (set("") -> clearProperty).
            assertNull(System.getProperty("dlv.path"))
        } finally {
            if (savedSystem == null) System.clearProperty("dlv.path")
            else System.setProperty("dlv.path", savedSystem)
        }
    }

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

    @Test
    fun `Run-on-Target two-phase build sub-execution clears then real debug re-swaps and restores cleanly`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)

        // Build sub-execution swaps then terminates, clearing back to original.
        swapper.swap("build", "/opt/dlv-arm")
        swapper.restore("build")
        assertEquals("/usr/bin/dlv", backend.get())

        // Real debug re-applies the override at process-start; its own processTerminated
        // restores so nothing leaks past the session.
        swapper.swap("debug", "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm", backend.get())

        swapper.restore("debug")
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `hasActiveSessions reflects swap and restore`() {
        val swapper = swapper(TestDlvPathBackend("/usr/bin/dlv"))
        assertFalse(swapper.hasActiveSessions())
        swapper.swap("a", "/opt/dlv-a")
        assertTrue(swapper.hasActiveSessions())
        swapper.restore("a")
        assertFalse(swapper.hasActiveSessions())
    }

    @Test
    fun `baseline reflects fresh backend value when no sessions are active`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)
        assertEquals("/usr/bin/dlv", swapper.baseline)

        // User edits the IDE-wide dlv.path between sessions; baseline picks up the new value.
        backend.set("/usr/local/bin/dlv")
        assertEquals("/usr/local/bin/dlv", swapper.baseline)
    }

    @Test
    fun `baseline returns captured original during an active swap`() {
        val backend = TestDlvPathBackend("/usr/bin/dlv")
        val swapper = swapper(backend)

        swapper.swap("a", "/opt/dlv-arm")
        // backend now holds /opt/dlv-arm, but baseline still resolves to the pre-swap value
        // so a concurrent GLOBAL-tier session asserts the user's IDE-wide setting, not a
        // sibling session's swap.
        assertEquals("/usr/bin/dlv", swapper.baseline)

        swapper.restore("a")
        assertEquals("/usr/bin/dlv", swapper.baseline)
    }

    @Test
    fun `a GLOBAL session asserts baseline instead of a concurrent custom session's leaked path`() {
        // Reproduces the reported bug: Project A (custom ARM) is debugging; Project B (global)
        // starts and must NOT inherit A's ARM binary.
        val backend = TestDlvPathBackend("")          // no IDE-wide dlv.path -> baseline is empty
        val swapper = swapper(backend)

        // Project A — custom ARM path — starts and swaps.
        swapper.swap("A", "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm", backend.get())

        // Project B — GLOBAL — starts while A is still active. It asserts the *baseline*
        // (the captured-original, not the currently-swapped ARM value), which overwrites the
        // leaked ARM path for its own startProcess read.
        swapper.swap("B", swapper.baseline)
        assertEquals("", backend.get())                // B sees the bundled/global default, not ARM

        // B ends first; A is still active so nothing is restored yet.
        swapper.restore("B")
        // A ends last; property returns to the captured baseline.
        swapper.restore("A")
        assertEquals("", backend.get())
    }

    @Test
    fun `resolveForLaunch for non-CUSTOM returns the source's mapped value with no warning`() {
        val global = resolveForLaunch(DelvePathSettings(source = DelveSource.GLOBAL), "/baseline/dlv", "/ignored")
        assertEquals(Resolution("/baseline/dlv"), global)

        val bundled = resolveForLaunch(DelvePathSettings(source = DelveSource.BUNDLED), "/baseline/dlv", "/ignored")
        assertEquals(Resolution(""), bundled)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)  // POSIX permissions
    fun `resolveForLaunch for CUSTOM with valid path returns expanded value with no warning`(@TempDir tmp: Path) {
        val dlv = Files.createFile(tmp.resolve("dlv"))
        Files.setPosixFilePermissions(dlv, PosixFilePermissions.fromString("rwxr-xr-x"))
        val r = resolveForLaunch(
            settings = DelvePathSettings(source = DelveSource.CUSTOM, path = dlv.toString()),
            baseline = "/baseline/dlv",
            expandedPath = dlv.toString(),
        )
        assertEquals(dlv.toString(), r.value)
        assertNull(r.warning)
    }

    @Test
    fun `resolveForLaunch for CUSTOM with missing file falls back to baseline with ERROR notice`() {
        val r = resolveForLaunch(
            settings = DelvePathSettings(source = DelveSource.CUSTOM, path = "/no/such/dlv"),
            baseline = "/baseline/dlv",
            expandedPath = "/no/such/dlv",
        )
        assertEquals("/baseline/dlv", r.value)
        assertNotNull(r.warning)
        assertEquals("Delve path override skipped", r.warning!!.title)
    }

    @Test
    fun `resolveForLaunch for CUSTOM with non-executable file uses the path but emits WARN notice`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv-noexec"))
        val r = resolveForLaunch(
            settings = DelvePathSettings(source = DelveSource.CUSTOM, path = f.toString()),
            baseline = "/baseline/dlv",
            expandedPath = f.toString(),
        )
        assertEquals(f.toString(), r.value)
        assertNotNull(r.warning)
        assertEquals("Delve path warning", r.warning!!.title)
    }
}

internal class TestDlvPathBackend(initial: String) : DlvPathBackend {
    @Volatile private var value: String = initial
    override fun get(): String = value
    override fun set(v: String) { value = v }
}

/** Construct a swapper with an in-memory sentinel, isolated from the host IDE's system path. */
private fun swapper(backend: DlvPathBackend): DlvPathSwapper {
    val sentinelFile = Files.createTempFile("delveswap-test-", ".sentinel")
    sentinelFile.toFile().deleteOnExit()
    return DlvPathSwapper(backend, SentinelStore(sentinelFile))
}
