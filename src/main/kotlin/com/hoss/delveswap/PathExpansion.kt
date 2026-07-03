package com.hoss.delveswap

import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project

internal object PathExpansion {
    // The unbraced form refuses to match a name directly followed by `$` — unless that
    // `$` opens a braced expansion (`${` can't occur inside macro syntax, so `$A${B}`
    // still expands). Possessive `*+` so the name can't backtrack to a shorter match that
    // dodges the lookahead. This keeps an unexpanded IntelliJ macro like `$GOPATH$/dlv`
    // intact for the validator to reject, instead of a same-named env var half-eating it
    // into `/home/u/go$/dlv`. (A macro directly followed by more name chars —
    // `$GOPATH$ARCH` — is still mangled; the result stays invalid and falls back to
    // baseline, just with an uglier notification.)
    private val ENV_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*+)(?!\$(?!\{))""")

    /** Pure function — trims, then expands $VAR and ${VAR} against the provided map. */
    fun expandEnv(input: String, env: Map<String, String>): String =
        ENV_PATTERN.replace(input.trim()) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            env[name] ?: match.value
        }

    /**
     * Full runtime expansion: IntelliJ path macros first, then env vars from the supplied map.
     *
     * The order is load-bearing: macro syntax (`$PROJECT_DIR$`) is also a prefix match for
     * [ENV_PATTERN]'s unbraced form, so expanding env vars first would eat `$PROJECT_DIR`
     * and leave a stray `$` whenever a same-named env var happens to exist.
     */
    fun expand(input: String, project: Project, env: Map<String, String>): String {
        if (input.isEmpty()) return input
        val macroExpanded = PathMacroManager.getInstance(project).expandPath(input)
        return expandEnv(macroExpanded, env)
    }
}
