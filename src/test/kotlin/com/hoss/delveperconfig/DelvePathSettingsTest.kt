package com.hoss.delveperconfig

import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DelvePathSettingsTest {
    @Test
    fun `default settings use global path and have empty path string`() {
        val s = DelvePathSettings()
        assertTrue(s.useGlobal)
        assertEquals("", s.path)
    }

    @Test
    fun `write then read round-trips values`() {
        val s = DelvePathSettings(useGlobal = false, path = "\$PROJECT_DIR\$/dlv")
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertFalse(loaded.useGlobal)
        assertEquals("\$PROJECT_DIR\$/dlv", loaded.path)
    }

    @Test
    fun `absent element decodes to defaults`() {
        val parent = Element("configuration")
        val loaded = DelvePathSettings().apply { readFrom(parent) }
        assertTrue(loaded.useGlobal)
        assertEquals("", loaded.path)
    }

    @Test
    fun `writeTo with default values writes no element`() {
        val parent = Element("configuration")
        DelvePathSettings().writeTo(parent)
        assertTrue(parent.children.isEmpty())
    }
}
