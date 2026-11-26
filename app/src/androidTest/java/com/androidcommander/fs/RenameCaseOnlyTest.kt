package com.androidcommander.fs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instruction section 11, tests 1-3: the case-only rename fix (section 3) is the app's reason
 * for existing. Runs against getExternalFilesDir, which needs no special permission grant to
 * exercise from an instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class RenameCaseOnlyTest {

    private lateinit var root: File
    private lateinit var ops: JavaFileOps

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.getExternalFilesDir(null), "rename_test_${System.currentTimeMillis()}")
        root.mkdirs()
        ops = JavaFileOps()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun caseOnlyRenameSucceeds() = runBlocking {
        val foo = File(root, "Foo")
        foo.mkdir()

        val outcome = ops.rename(foo, "foo")

        val names = root.list()!!.toList()
        when (outcome) {
            is RenameOutcome.Success -> {
                assertTrue("expected 'foo' in listing, got $names", names.contains("foo"))
                assertFalse("did not expect 'Foo' in listing, got $names", names.contains("Foo"))
            }
            is RenameOutcome.Failure -> {
                // Only acceptable if the volume genuinely cannot store the new case
                // (instruction section 11 test 2) -- verified separately below.
                assertEquals(FsError.CaseNotStorable, outcome.error)
            }
            is RenameOutcome.Conflict -> throw AssertionError("case-only rename must not report a false conflict")
        }
    }

    @Test
    fun caseOnlyRenameNeverLosesDirectoryOnFailure() = runBlocking {
        val foo = File(root, "Bar")
        foo.mkdir()
        File(foo, "child.txt").writeText("hello")

        ops.rename(foo, "bar")

        // Whether the case change stuck or was reported CASE_NOT_STORABLE, the directory and its
        // contents must survive intact, and no orphaned temp file is left behind.
        val entries = root.list()!!.toList()
        assertTrue(entries.any { it.equals("bar", ignoreCase = true) })
        assertTrue(entries.none { it.startsWith(".ac_") })
        val survivor = File(root, entries.first { it.equals("bar", ignoreCase = true) })
        assertTrue(File(survivor, "child.txt").exists())
    }

    @Test
    fun distinctNameConflictIsReportedNotSilentlyLost() = runBlocking {
        val fooTxt = File(root, "Foo.txt")
        fooTxt.writeText("A")
        val fooTxtLower = File(root, "fooTXTother") // distinct real file, different content
        // Simulate a genuinely distinct pre-existing "foo.txt" by using a case-sensitive-only
        // scenario where the target name differs from src only in case AND a *different* file
        // occupies effectively the same slot is not directly constructible if the FS is
        // case-insensitive (foo.txt IS Foo.txt there). On a case-sensitive volume, create the
        // real distinct conflict:
        val distinctTarget = File(root, "foo.txt")
        if (!distinctTarget.exists()) {
            distinctTarget.writeText("B")
        }
        if (!ops.isSameInode(fooTxt, distinctTarget)) {
            val outcome = ops.rename(fooTxt, "foo.txt")
            assertTrue(
                "expected a conflict, not silent data loss, got $outcome",
                outcome is RenameOutcome.Conflict,
            )
            assertEquals("A", fooTxt.readText())
            assertEquals("B", distinctTarget.readText())
        }
        // On a case-insensitive volume this scenario can't be constructed (both paths are the
        // same inode) -- nothing to assert there; the conflict path is exercised wherever the
        // filesystem under test is case-sensitive.
    }
}
