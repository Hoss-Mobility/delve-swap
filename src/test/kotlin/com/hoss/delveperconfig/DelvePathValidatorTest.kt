package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DelvePathValidatorTest {

    @Test
    fun `empty path with override enabled is an error`() {
        val r = DelvePathValidator.validateExpanded(expandedPath = "", overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.ERROR_EMPTY, r)
    }

    @Test
    fun `empty path with override disabled is ok`() {
        val r = DelvePathValidator.validateExpanded(expandedPath = "", overrideEnabled = false)
        assertEquals(DelvePathValidator.Result.OK_USE_GLOBAL, r)
    }

    @Test
    fun `nonexistent file is an error`(@TempDir tmp: Path) {
        val missing = tmp.resolve("nope").toString()
        val r = DelvePathValidator.validateExpanded(missing, overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.ERROR_NOT_FOUND, r)
    }

    @Test
    fun `non-executable file is a warning`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv-noexec"))
        val r = DelvePathValidator.validateExpanded(f.toString(), overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.WARN_NOT_EXECUTABLE, r)
    }

    @Test
    fun `executable file is ok`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv"))
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
        val r = DelvePathValidator.validateExpanded(f.toString(), overrideEnabled = true)
        assertEquals(DelvePathValidator.Result.OK, r)
    }
}
