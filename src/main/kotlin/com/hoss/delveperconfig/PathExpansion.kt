package com.hoss.delveperconfig

import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project

object PathExpansion {
    private val ENV_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    /** Pure function — trims, then expands $VAR and ${VAR} against the provided map. Used by tests and runtime. */
    fun expandEnv(input: String, env: Map<String, String>): String =
        ENV_PATTERN.replace(input.trim()) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            env[name] ?: match.value
        }

    /** Full runtime expansion: IntelliJ path macros first, then env vars from the supplied map. */
    fun expand(input: String, project: Project, env: Map<String, String>): String {
        if (input.isEmpty()) return input
        val macroExpanded = PathMacroManager.getInstance(project).expandPath(input)
        return expandEnv(macroExpanded, env)
    }
}
