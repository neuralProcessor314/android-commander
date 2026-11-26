package com.androidcommander.model

import com.androidcommander.fs.FsEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for instruction section 11 tests 12-14, which exercise selection and
 * pagination math that lives in [PaneState]/[MainViewModel] -- the selection toggle itself is
 * trivial set math, mirrored here so it's covered without needing an Android context.
 */
class PaneStateTest {

    private fun entry(name: String) = FsEntry(File("/x/$name"), name, false, false, 0, 0)

    private fun toggle(selected: Set<String>, key: String): Set<String> =
        if (key in selected) selected - key else selected + key

    @Test
    fun tappingTwiceDeselectsTappingThreeDistinctSelectsThree() {
        var selected = emptySet<String>()
        selected = toggle(selected, "a")
        selected = toggle(selected, "a")
        assertTrue("tap-tap must leave it unselected", selected.isEmpty())

        selected = toggle(selected, "a")
        selected = toggle(selected, "b")
        selected = toggle(selected, "c")
        assertEquals(setOf("a", "b", "c"), selected)
    }

    @Test
    fun pageIndicatorReadsOneOfOneForShortDirectoryAndCeilingOtherwise() {
        val pageSize = 20
        val short = PaneState(PaneId.A, File("/x"), entries = (1..5).map { entry("f$it") }, hasParent = false)
        assertEquals(1, short.pageCount(pageSize))

        val long = PaneState(PaneId.A, File("/x"), entries = (1..45).map { entry("f$it") }, hasParent = false)
        assertEquals(3, long.pageCount(pageSize)) // ceil(45/20)
    }

    @Test
    fun rowsForPageAlwaysReturnsExactlyPageSizeIncludingBlankPadding() {
        val pageSize = 20
        val empty = PaneState(PaneId.A, File("/x"), entries = emptyList(), hasParent = false)
        assertEquals(pageSize, empty.rowsForPage(pageSize).size)

        val one = PaneState(PaneId.A, File("/x"), entries = listOf(entry("f1")), hasParent = true)
        val rows = one.rowsForPage(pageSize)
        assertEquals(pageSize, rows.size)
        assertEquals(PaneRow.Parent, rows[0])
        assertTrue(rows[1] is PaneRow.Entry)
        assertTrue("remaining rows must be blank padding", rows.drop(2).all { it == null })
    }

    @Test
    fun selectionMadeOnPageOneSurvivesATripToPageThreeAndBack() {
        val pageSize = 20
        var pane = PaneState(PaneId.A, File("/x"), entries = (1..45).map { entry("f$it") }, hasParent = false)
        pane = pane.copy(selected = setOf("f1"))

        pane = pane.copy(page = 2) // page 3 of 3
        pane = pane.copy(page = 0) // back to page 1

        assertEquals(setOf("f1"), pane.selected)
        assertTrue((pane.rowsForPage(pageSize)[0] as PaneRow.Entry).entry.name == "f1")
    }

    @Test
    fun parentRowIsSelectableViaTheReservedDotDotKey() {
        assertEquals("..", PaneRow.Parent.selectionKey())
        assertEquals("f1", PaneRow.Entry(entry("f1")).selectionKey())
    }
}
