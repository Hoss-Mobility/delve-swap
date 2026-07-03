package com.hoss.delveswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SentinelRecoveryTest {

    @Test
    fun `null sentinel yields None`() {
        assertEquals(RecoveryAction.None, recoveryAction(null))
    }

    @Test
    fun `blank sentinel yields Clear`() {
        assertEquals(RecoveryAction.Clear, recoveryAction("   "))
    }

    @Test
    fun `nonexistent path yields Discard`() {
        assertEquals(RecoveryAction.Discard, recoveryAction("/no/such/dlv-binary-xyz"))
    }

    @Test
    fun `valid executable path yields Restore`(@TempDir dir: Path) {
        val exe = dir.resolve("dlv")
        Files.writeString(exe, "#!/bin/sh\n")
        exe.toFile().setExecutable(true)
        assertEquals(RecoveryAction.Restore(exe.toString()), recoveryAction(exe.toString()))
    }

    @Test
    fun `SentinelStore writes then reads back the value`(@TempDir dir: Path) {
        val s = SentinelStore(dir.resolve("sentinel"))
        s.write("/usr/bin/dlv")
        assertEquals("/usr/bin/dlv", s.readAndClear())
    }

    @Test
    fun `SentinelStore readAndClear deletes the file`(@TempDir dir: Path) {
        val file = dir.resolve("sentinel")
        val s = SentinelStore(file)
        s.write("/usr/bin/dlv")
        s.readAndClear()
        assertFalse(Files.exists(file))
        assertNull(s.readAndClear())  // second read after clear is empty
    }

    @Test
    fun `SentinelStore clear is a no-op when file is absent`(@TempDir dir: Path) {
        val s = SentinelStore(dir.resolve("sentinel"))
        s.clear()  // must not throw
        assertNull(s.readAndClear())
    }

    @Test
    fun `SentinelStore preserves blank originals so recovery can Clear correctly`(@TempDir dir: Path) {
        val s = SentinelStore(dir.resolve("sentinel"))
        s.write("")  // captures "original was unset"
        assertEquals("", s.readAndClear())
    }

    @Test
    fun `SentinelStore creates parent directory on first write`(@TempDir dir: Path) {
        val s = SentinelStore(dir.resolve("nested/dir/sentinel"))
        s.write("/opt/dlv")
        assertEquals("/opt/dlv", s.readAndClear())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)  // POSIX permissions
    fun `SentinelStore readAndClear returns the value even when the delete fails`(@TempDir dir: Path) {
        val parent = dir.resolve("locked")
        val s = SentinelStore(parent.resolve("sentinel"))
        s.write("/usr/bin/dlv")
        // Deleting a file needs write permission on its parent; drop it to force the
        // delete to fail after a successful read.
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertEquals("/usr/bin/dlv", s.readAndClear())
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
