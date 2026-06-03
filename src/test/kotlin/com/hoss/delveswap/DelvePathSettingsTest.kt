package com.hoss.delveswap

import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelvePathSettingsTest {
    @Test
    fun `default settings use GLOBAL source and empty path`() {
        val s = DelvePathSettings()
        assertEquals(DelveSource.GLOBAL, s.source)
        assertEquals("", s.path)
    }

    @Test
    fun `custom source round-trips source and path`() {
        val s = DelvePathSettings(source = DelveSource.CUSTOM, path = "\$PROJECT_DIR\$/dlv")
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.CUSTOM, loaded.source)
        assertEquals("\$PROJECT_DIR\$/dlv", loaded.path)
    }

    @Test
    fun `bundled source round-trips`() {
        val s = DelvePathSettings(source = DelveSource.BUNDLED)
        val parent = Element("configuration")
        s.writeTo(parent)

        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.BUNDLED, loaded.source)
    }

    @Test
    fun `absent element decodes to defaults`() {
        val parent = Element("configuration")
        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.GLOBAL, loaded.source)
        assertEquals("", loaded.path)
    }

    @Test
    fun `writeTo with default values writes no element`() {
        val parent = Element("configuration")
        DelvePathSettings().writeTo(parent)
        assertEquals(true, parent.children.isEmpty())
    }

    @Test
    fun `legacy useGlobal=false migrates to CUSTOM`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute("useGlobal", "false")
                .setAttribute(DelvePathSettings.ATTR_PATH, "/opt/dlv-arm"),
        )
        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.CUSTOM, loaded.source)
        assertEquals("/opt/dlv-arm", loaded.path)
    }

    @Test
    fun `legacy useGlobal=true migrates to GLOBAL`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute("useGlobal", "true")
                .setAttribute(DelvePathSettings.ATTR_PATH, ""),
        )
        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.GLOBAL, loaded.source)
    }

    @Test
    fun `new source attribute takes precedence over legacy useGlobal`() {
        val parent = Element("configuration")
        parent.addContent(
            Element(DelvePathSettings.ELEMENT_NAME)
                .setAttribute(DelvePathSettings.ATTR_SOURCE, "BUNDLED")
                .setAttribute("useGlobal", "false"),
        )
        val loaded = DelvePathSettings.readFrom(parent)
        assertEquals(DelveSource.BUNDLED, loaded.source)
    }
}
