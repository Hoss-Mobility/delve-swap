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
