package com.hoss.delveperconfig

import java.nio.file.Files
import java.nio.file.Path

object DelvePathValidator {

    enum class Result(val message: String, val severity: Severity) {
        OK("OK", Severity.OK),
        OK_USE_GLOBAL("Will use global dlv.path", Severity.OK),
        ERROR_EMPTY("Delve path is empty", Severity.ERROR),
        ERROR_NOT_FOUND("File does not exist", Severity.ERROR),
        WARN_NOT_EXECUTABLE("File is not executable", Severity.WARN),
    }

    enum class Severity { OK, WARN, ERROR }

    fun validateExpanded(expandedPath: String, overrideEnabled: Boolean): Result {
        if (!overrideEnabled) return Result.OK_USE_GLOBAL
        if (expandedPath.isBlank()) return Result.ERROR_EMPTY
        val p: Path = Path.of(expandedPath)
        if (!Files.exists(p)) return Result.ERROR_NOT_FOUND
        if (!Files.isExecutable(p)) return Result.WARN_NOT_EXECUTABLE
        return Result.OK
    }
}
