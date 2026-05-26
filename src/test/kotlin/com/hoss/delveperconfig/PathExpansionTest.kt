package com.hoss.delveperconfig

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
}
