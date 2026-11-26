package com.androidcommander.model

import com.androidcommander.fs.FsEntry
import java.io.File

enum class PaneId { A, B }

/**
 * One pane's full state (instruction section 5/7.2). `entries` always includes the synthetic
 * `..` row at index 0 (except at a filesystem root). Pagination is index math over this flat
 * list, never a scrollable list.
 */
data class PaneState(
    val id: PaneId,
    val path: File,
    val entries: List<FsEntry> = emptyList(),
    val hasParent: Boolean = true,
    val page: Int = 0, // 0-indexed
    val selected: Set<String> = emptySet(), // entry names selected in this directory
    val freeBytes: Long = 0L,
) {
    /** Row 0 is the synthetic `..`; real entries follow. Matches instruction section 5/7.2. */
    val rows: List<PaneRow>
        get() = buildList {
            if (hasParent) add(PaneRow.Parent)
            entries.forEach { add(PaneRow.Entry(it)) }
        }

    fun pageCount(pageSize: Int): Int = maxOf(1, (rows.size + pageSize - 1) / pageSize)

    fun rowsForPage(pageSize: Int): List<PaneRow?> {
        val start = page * pageSize
        val slice = rows.drop(start).take(pageSize)
        return slice + List(pageSize - slice.size) { null }
    }
}

sealed interface PaneRow {
    data object Parent : PaneRow
    data class Entry(val entry: FsEntry) : PaneRow
}

/** ".." carries the reserved key ".." -- it is selectable like any entry (instruction section 7.2). */
fun PaneRow.selectionKey(): String? = when (this) {
    PaneRow.Parent -> ".."
    is PaneRow.Entry -> entry.name
}
