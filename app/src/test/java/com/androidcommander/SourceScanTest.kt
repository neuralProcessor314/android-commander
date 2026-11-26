package com.androidcommander

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instruction section 11, test 9: a grep for LongPress/onLongClick/LazyColumn/verticalScroll in
 * the source must return nothing. Runs as a plain JVM test (no device needed) so it's always in
 * the loop, not just when someone remembers to run the instrumented suite.
 */
class SourceScanTest {

    private val bannedPatterns = listOf(
        "LongPress",
        "onLongClick",
        "combinedClickable",
        "LazyColumn",
        "verticalScroll",
    )

    private fun mainSourceRoot(): File {
        // Gradle's working directory for :app unit tests is the app/ module directory.
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull { it.exists() }
            ?: error("could not locate src/main from ${File(".").absolutePath}")
    }

    @Test
    fun sourceContainsNoBannedApis() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (pattern in bannedPatterns) {
                    if (text.contains(pattern)) {
                        violations += "${file.path}: contains \"$pattern\""
                    }
                }
            }

        assertTrue(
            "Banned APIs found (instruction section 0.3/5 forbid long-press and scrollable lists):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
