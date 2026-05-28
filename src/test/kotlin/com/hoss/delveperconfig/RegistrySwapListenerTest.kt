package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
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
}

internal class TestRegistryBackend(initial: String) : RegistryBackend {
    @Volatile private var value: String = initial
    override fun get(): String = value
    override fun set(v: String) { value = v }
}
