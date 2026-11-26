package com.androidcommander.util

import com.androidcommander.fs.FsEntry

/** Instruction section 6: directories first, then files, case-insensitive name ascending. Fixed, no sort UI. */
val entryComparator: Comparator<FsEntry> =
    compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() }

fun List<FsEntry>.sortedForDisplay(): List<FsEntry> = sortedWith(entryComparator)
