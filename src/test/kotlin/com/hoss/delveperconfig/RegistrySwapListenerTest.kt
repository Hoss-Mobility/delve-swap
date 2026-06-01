package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegistrySwapListenerTest {

    @Test
    fun `swap-restore on a single execution returns to original value`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)

        val sessionId = "exec-1"
        swapper.swap(sessionId, "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm", backend.get())

        swapper.restore(sessionId)
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `overlapping sessions both see their swap and original restored only after last release`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)

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
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)
        swapper.restore("never-swapped")  // must not throw
        assertEquals("/usr/bin/dlv", backend.get())
    }

    @Test
    fun `concurrent swaps from two threads do not corrupt the original value`() {
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)
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
        val backend = TestRegistryBackend("/usr/bin/dlv")
        val swapper = RegistrySwapper(backend)

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
            val swapper = RegistrySwapper(SystemPropertyBackend())

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
    fun `PlatformRegistryBackend delegates reads and writes to its inner backend`() {
        val inner = TestRegistryBackend("/initial")
        val backend = PlatformRegistryBackend(inner)

        assertEquals("/initial", backend.get())
        backend.set("/opt/dlv-armv7")
        assertEquals("/opt/dlv-armv7", inner.get())
        assertEquals("/opt/dlv-armv7", backend.get())
    }

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

    @Test
    fun `hasActiveSessions reflects swap and restore`() {
        val swapper = RegistrySwapper(TestRegistryBackend("/usr/bin/dlv"))
        assertFalse(swapper.hasActiveSessions())
        swapper.swap("a", "/opt/dlv-a")
        assertTrue(swapper.hasActiveSessions())
        swapper.restore("a")
        assertFalse(swapper.hasActiveSessions())
    }

    @Test
    fun `baseline captures the backend value at construction`() {
        val swapper = RegistrySwapper(TestRegistryBackend("/usr/bin/dlv"))
        assertEquals("/usr/bin/dlv", swapper.baseline)

        // Baseline is immutable — swapping does not change it.
        swapper.swap("a", "/opt/dlv-arm")
        assertEquals("/usr/bin/dlv", swapper.baseline)
    }

    @Test
    fun `resolveDesiredValue maps each source to its asserted value`() {
        assertEquals("", resolveDesiredValue(DelveSource.BUNDLED, "/baseline/dlv", "/custom/dlv"))
        assertEquals("/baseline/dlv", resolveDesiredValue(DelveSource.GLOBAL, "/baseline/dlv", "/custom/dlv"))
        assertEquals("/custom/dlv", resolveDesiredValue(DelveSource.CUSTOM, "/baseline/dlv", "/custom/dlv"))
    }

    @Test
    fun `a GLOBAL session asserts baseline instead of a concurrent custom session's leaked path`() {
        // Reproduces the reported bug: Project A (custom ARM) is debugging; Project B (global)
        // starts and must NOT inherit A's ARM binary.
        val backend = TestRegistryBackend("")          // no IDE-wide dlv.path -> baseline is empty
        val swapper = RegistrySwapper(backend)

        // Project A — custom ARM path — starts and swaps.
        swapper.swap("A", resolveDesiredValue(DelveSource.CUSTOM, swapper.baseline, "/opt/dlv-arm"))
        assertEquals("/opt/dlv-arm", backend.get())

        // Project B — GLOBAL — starts while A is still active. It asserts the baseline,
        // overwriting the leaked ARM path for its own startProcess read.
        swapper.swap("B", resolveDesiredValue(DelveSource.GLOBAL, swapper.baseline, ""))
        assertEquals("", backend.get())                // B sees the bundled/global default, not ARM

        // B ends first; A is still active so nothing is restored yet.
        swapper.restore("B")
        // A ends last; property returns to the captured baseline.
        swapper.restore("A")
        assertEquals("", backend.get())
    }
}

internal class TestRegistryBackend(initial: String) : RegistryBackend {
    @Volatile private var value: String = initial
    override fun get(): String = value
    override fun set(v: String) { value = v }
}
