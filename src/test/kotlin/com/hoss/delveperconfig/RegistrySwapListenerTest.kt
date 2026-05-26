package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
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

    private class TestRegistryBackend(initial: String) : RegistryBackend {
        @Volatile private var value: String = initial
        override fun get(): String = value
        override fun set(v: String) { value = v }
    }
}
