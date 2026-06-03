package com.hoss.delveswap

import java.nio.file.Files
import java.nio.file.Path

internal object DelvePathValidator {

    enum class Result(val message: String, val severity: Severity) {
        OK("OK", Severity.OK),
        ERROR_EMPTY("Delve path is empty", Severity.ERROR),
        ERROR_NOT_FOUND("File does not exist", Severity.ERROR),
        WARN_NOT_EXECUTABLE("File is not executable", Severity.WARN),
    }

    enum class Severity { OK, WARN, ERROR }

    fun validate(expandedPath: String): Result {
        if (expandedPath.isBlank()) return Result.ERROR_EMPTY
        val p: Path = Path.of(expandedPath)
        if (!Files.exists(p)) return Result.ERROR_NOT_FOUND
        // Files.isExecutable on Windows reflects the file's "readable" attribute, not POSIX
        // execute permission, so this check almost always passes there. Acceptable: Windows
        // uses .exe semantics and a non-readable file would also fail to launch.
        if (!Files.isExecutable(p)) return Result.WARN_NOT_EXECUTABLE
        return Result.OK
    }
}
