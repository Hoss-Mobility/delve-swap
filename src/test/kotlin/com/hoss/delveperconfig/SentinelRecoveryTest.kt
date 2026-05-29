package com.hoss.delveperconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
}
