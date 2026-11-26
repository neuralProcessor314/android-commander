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
 * Instruction section 11, tests 4-6: moving into a directory whose name already exists at the
 * destination must merge, never abort the whole operation (instruction section 4). This is the
 * app's second reason for existing.
 */
@RunWith(AndroidJUnit4::class)
class MoveMergeTest {

    private lateinit var root: File
    private lateinit var ops: JavaFileOps

    private fun neverAsked(src: File, target: File): Action =
        throw AssertionError("no conflict expected in this scenario: $src -> $target")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.getExternalFilesDir(null), "move_test_${System.currentTimeMillis()}")
        root.mkdirs()
        ops = JavaFileOps()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun mergeWithDisjointChildrenUnionsAndRemovesSource() = runBlocking {
        val srcFoo = File(root, "foo").apply { mkdir() }
        File(srcFoo, "a.txt").writeText("a")
        val destBar = File(root, "bar").apply { mkdir() }
        val destBarFoo = File(destBar, "foo").apply { mkdir() }
        File(destBarFoo, "b.txt").writeText("b")

        val policy = ConflictPolicy(resolve = ::neverAsked)
        val result = ops.move(listOf(srcFoo), destBar, policy) {}

        assertFalse("source ~/foo must be gone", srcFoo.exists())
        assertTrue(File(destBarFoo, "a.txt").exists())
        assertTrue(File(destBarFoo, "b.txt").exists())
        assertEquals(1, result.okCount)
    }

    @Test
    fun mergeWithCollidingFileAsksExactlyOnceThenOverwriteAllResolvesRest() = runBlocking {
        val srcFoo = File(root, "foo").apply { mkdir() }
        File(srcFoo, "collide.txt").writeText("new-1")
        File(srcFoo, "collide2.txt").writeText("new-2")
        val destBar = File(root, "bar").apply { mkdir() }
        val destBarFoo = File(destBar, "foo").apply { mkdir() }
        File(destBarFoo, "collide.txt").writeText("old-1")
        File(destBarFoo, "collide2.txt").writeText("old-2")

        var askCount = 0
        lateinit var policy: ConflictPolicy
        policy = ConflictPolicy(
            resolve = { _, _ ->
                askCount++
                policy.rememberedAction = Action.OVERWRITE
                Action.OVERWRITE
            },
        )
        val result = ops.move(listOf(srcFoo), destBar, policy) {}

        assertEquals("exactly one prompt, then OVERWRITE ALL resolves the rest", 1, askCount)
        assertEquals("new-1", File(destBarFoo, "collide.txt").readText())
        assertEquals("new-2", File(destBarFoo, "collide2.txt").readText())
        assertFalse(srcFoo.exists())
    }

    @Test
    fun movingIntoOwnDescendantIsRejectedWithoutModifyingAnything() = runBlocking {
        val srcFoo = File(root, "foo").apply { mkdir() }
        File(srcFoo, "keep.txt").writeText("x")
        val sub = File(srcFoo, "sub").apply { mkdir() }

        val policy = ConflictPolicy(resolve = ::neverAsked)
        val result = ops.move(listOf(srcFoo), sub, policy) {}

        assertEquals(0, result.okCount)
        assertTrue(result.reports.any { it is OpReport.Fail && it.error == FsError.ErrIntoSelf })
        assertTrue(srcFoo.exists())
        assertTrue(File(srcFoo, "keep.txt").exists())
        assertTrue(sub.exists())
    }

    @Test
    fun crossVolumeStyleFallbackCopiesVerifiesThenDeletesSource() = runBlocking {
        // JavaFileOps' EXDEV fallback (copyTree + verifyTree + deleteTree) is exercised directly
        // here since forcing a genuine cross-filesystem-boundary rename failure from a test isn't
        // portable; the same code path is what renameTo()-returning-false falls through to.
        val srcDir = File(root, "src_tree").apply { mkdir() }
        File(srcDir, "one.bin").writeBytes(ByteArray(1024) { it.toByte() })
        val nested = File(srcDir, "nested").apply { mkdir() }
        File(nested, "two.bin").writeBytes(ByteArray(2048) { it.toByte() })

        val dst = File(root, "dst_tree")
        ops.copyTree(srcDir, dst)
        ops.verifyTree(srcDir, dst) // throws on mismatch -- reaching here is the assertion
        ops.deleteTree(srcDir)

        assertFalse(srcDir.exists())
        assertTrue(File(dst, "one.bin").exists())
        assertTrue(File(dst, "nested/two.bin").exists())
        assertEquals(1024, File(dst, "one.bin").length().toInt())
    }
}
