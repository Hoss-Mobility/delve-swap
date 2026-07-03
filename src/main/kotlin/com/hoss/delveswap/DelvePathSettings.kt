package com.hoss.delveswap

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.util.Key
import org.jdom.Element

/** Which Delve binary a Go run configuration should debug with. */
internal enum class DelveSource {
    /** GoLand's shipped dlv — resolves to "" so the Go plugin falls back to its bundled binary. */
    BUNDLED,

    /** The IDE-wide `dlv.path`, re-read at each launch. */
    GLOBAL,

    /** A per-config path; macros and env vars are expanded at launch, then validated. */
    CUSTOM,
}

internal data class DelvePathSettings(
    val source: DelveSource = DelveSource.GLOBAL,
    val path: String = "",
) {
    fun writeTo(parent: Element) {
        // Default state is the absence of the element — keeps committed XML clean for configs
        // that never opt in.
        if (source == DelveSource.GLOBAL && path.isEmpty()) return
        parent.addContent(
            Element(ELEMENT_NAME).apply {
                setAttribute(ATTR_SOURCE, source.name)
                setAttribute(ATTR_PATH, path)
            },
        )
    }

    companion object {
        const val ELEMENT_NAME = "delvePathSettings"
        const val ATTR_SOURCE = "source"
        const val ATTR_PATH = "path"
        private const val ATTR_USE_GLOBAL_LEGACY = "useGlobal"

        fun readFrom(parent: Element): DelvePathSettings {
            val el = parent.getChild(ELEMENT_NAME) ?: return DelvePathSettings()
            val sourceAttr = el.getAttributeValue(ATTR_SOURCE)
            val source = when {
                // Hand-edited or corrupt XML degrades to the default instead of failing the
                // whole run-config load.
                sourceAttr != null ->
                    runCatching { DelveSource.valueOf(sourceAttr) }.getOrDefault(DelveSource.GLOBAL)
                // v0.1.0 configs persisted a `useGlobal` boolean instead of `source`.
                el.getAttributeValue(ATTR_USE_GLOBAL_LEGACY)?.toBoolean() == false -> DelveSource.CUSTOM
                else -> DelveSource.GLOBAL
            }
            return DelvePathSettings(source = source, path = el.getAttributeValue(ATTR_PATH) ?: "")
        }
    }
}

private val DELVE_SETTINGS_KEY = Key.create<DelvePathSettings>("delveswap.settings")

// An absent key means "never configured", which is exactly what the defaults express — a
// pure read; nothing needs to be cached back onto the config.
internal fun RunConfigurationBase<*>.getDelveSettings(): DelvePathSettings =
    getUserData(DELVE_SETTINGS_KEY) ?: DelvePathSettings()

internal fun RunConfigurationBase<*>.setDelveSettings(settings: DelvePathSettings) {
    putUserData(DELVE_SETTINGS_KEY, settings)
}
