package com.hoss.delveperconfig

import org.jdom.Element

/** Where a run config gets its Delve binary. */
enum class DelveSource { BUNDLED, GLOBAL, CUSTOM }

class DelvePathSettings(
    var source: DelveSource = DelveSource.GLOBAL,
    var path: String = "",
) {
    fun writeTo(parent: Element) {
        if (source == DelveSource.GLOBAL && path.isEmpty()) return  // default state — no element
        val el = Element(ELEMENT_NAME).apply {
            setAttribute(ATTR_SOURCE, source.name)
            setAttribute(ATTR_PATH, path)
        }
        parent.addContent(el)
    }

    fun readFrom(parent: Element) {
        val el = parent.getChild(ELEMENT_NAME) ?: return
        val sourceAttr = el.getAttributeValue(ATTR_SOURCE)
        source = when {
            sourceAttr != null ->
                runCatching { DelveSource.valueOf(sourceAttr) }.getOrDefault(DelveSource.GLOBAL)
            // Legacy v1.x configs persisted a `useGlobal` boolean instead of `source`.
            el.getAttributeValue(ATTR_USE_GLOBAL_LEGACY)?.toBoolean() == false -> DelveSource.CUSTOM
            else -> DelveSource.GLOBAL
        }
        path = el.getAttributeValue(ATTR_PATH) ?: ""
    }

    fun copy(): DelvePathSettings = DelvePathSettings(source, path)

    companion object {
        const val ELEMENT_NAME = "delvePathSettings"
        const val ATTR_SOURCE = "source"
        const val ATTR_PATH = "path"
        const val ATTR_USE_GLOBAL_LEGACY = "useGlobal"
    }
}
