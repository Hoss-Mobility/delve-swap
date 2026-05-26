package com.hoss.delveperconfig

import org.jdom.Element

class DelvePathSettings(
    var useGlobal: Boolean = true,
    var path: String = "",
) {
    fun writeTo(parent: Element) {
        if (useGlobal && path.isEmpty()) return  // default state — no element
        val el = Element(ELEMENT_NAME).apply {
            setAttribute(ATTR_USE_GLOBAL, useGlobal.toString())
            setAttribute(ATTR_PATH, path)
        }
        parent.addContent(el)
    }

    fun readFrom(parent: Element) {
        val el = parent.getChild(ELEMENT_NAME) ?: return
        useGlobal = el.getAttributeValue(ATTR_USE_GLOBAL)?.toBoolean() ?: true
        path = el.getAttributeValue(ATTR_PATH) ?: ""
    }

    fun copy(): DelvePathSettings = DelvePathSettings(useGlobal, path)

    companion object {
        const val ELEMENT_NAME = "delvePathSettings"
        const val ATTR_USE_GLOBAL = "useGlobal"
        const val ATTR_PATH = "path"
    }
}
