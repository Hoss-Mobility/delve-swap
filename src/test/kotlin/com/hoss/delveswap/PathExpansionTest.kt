package com.hoss.delveswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathExpansionTest {
    @Test
    fun `unbraced env var is expanded`() {
        val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("\$ARM_DLV/bin", env))
    }

    @Test
    fun `braced env var is expanded`() {
        val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("\${ARM_DLV}/bin", env))
    }

    @Test
    fun `missing env var is left literally`() {
        val env = emptyMap<String, String>()
        assertEquals("\$MISSING/x", PathExpansion.expandEnv("\$MISSING/x", env))
    }

    @Test
    fun `multiple vars in one string`() {
        val env = mapOf("A" to "alpha", "B" to "beta")
        assertEquals("alpha/beta", PathExpansion.expandEnv("\$A/\${B}", env))
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals("", PathExpansion.expandEnv("", emptyMap()))
    }

    @Test
    fun `surrounding whitespace is trimmed before expansion`() {
        val env = mapOf("ARM_DLV" to "/opt/dlv-arm")
        assertEquals("/opt/dlv-arm/bin", PathExpansion.expandEnv("  \$ARM_DLV/bin  ", env))
    }

    @Test
    fun `unexpanded IntelliJ-style macro is not half-eaten by a same-named env var`() {
        // $GOPATH$ is macro syntax the IDE didn't expand (unknown macro). A GOPATH env var
        // must not turn it into a mangled "/home/u/go$/dlv" — leave it for the validator.
        val env = mapOf("GOPATH" to "/home/u/go")
        assertEquals("\$GOPATH\$/dlv", PathExpansion.expandEnv("\$GOPATH\$/dlv", env))
    }

    @Test
    fun `macro-like token is not rescued by backtracking onto a shorter env var name`() {
        // Possessive name match: $GOPATH$ must not fall back to expanding a "GOPAT" env var.
        val env = mapOf("GOPAT" to "/oops")
        assertEquals("\$GOPATH\$/dlv", PathExpansion.expandEnv("\$GOPATH\$/dlv", env))
    }

    @Test
    fun `env var adjacent to unexpanded macro still expands`() {
        val env = mapOf("GOPATH" to "/home/u/go", "ARCH" to "armv7")
        assertEquals("\$GOPATH\$/dlv-armv7", PathExpansion.expandEnv("\$GOPATH\$/dlv-\$ARCH", env))
    }

    @Test
    fun `unbraced var directly followed by a braced var expands both`() {
        // `${` can't occur inside $NAME$ macro syntax, so this is unambiguous env-var use.
        val env = mapOf("A" to "alpha", "B" to "beta")
        assertEquals("alphabeta", PathExpansion.expandEnv("\$A\${B}", env))
    }
}
