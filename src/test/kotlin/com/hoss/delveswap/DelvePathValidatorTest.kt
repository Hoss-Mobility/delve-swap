package com.hoss.delveswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DelvePathValidatorTest {

    @Test
    fun `empty path is an error`() {
        assertEquals(DelvePathValidator.Result.ERROR_EMPTY, DelvePathValidator.validate(""))
    }

    @Test
    fun `nonexistent file is an error`(@TempDir tmp: Path) {
        assertEquals(
            DelvePathValidator.Result.ERROR_NOT_FOUND,
            DelvePathValidator.validate(tmp.resolve("nope").toString()),
        )
    }

    @Test
    fun `non-executable file is a warning`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv-noexec"))
        assertEquals(DelvePathValidator.Result.WARN_NOT_EXECUTABLE, DelvePathValidator.validate(f.toString()))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)  // POSIX permissions
    fun `executable file is ok`(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("dlv"))
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals(DelvePathValidator.Result.OK, DelvePathValidator.validate(f.toString()))
    }
}
