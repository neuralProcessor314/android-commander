package com.androidcommander.model

/**
 * Every possible user action, queued in arrival order through an unbounded channel (instruction
 * section 7.1) so taps faster than the frame rate are never dropped or coalesced.
 */
sealed interface Command {
    data object Tab : Command
    data object Swap : Command
    data object Prev : Command
    data object Next : Command
    /** Go up one directory in the active pane (replaces the old OPEN bar button; OK now opens). */
    data object Up : Command
    data object Explore : Command
    data object SelectAll : Command
    data object SelectNone : Command
    data object Copy : Command
    data object Move : Command
    data object Rename : Command
    data object Mkdir : Command
    data object Delete : Command
    data object Ok : Command
    data object Cancel : Command
    data object Exit : Command

    data class TapEntry(val pane: PaneId, val rowIndex: Int) : Command
    data class SwipePane(val pane: PaneId, val forward: Boolean) : Command
    data class FocusPane(val pane: PaneId) : Command

    data class ImeText(val text: String) : Command
    data class ImeDigit(val digit: Char) : Command
    data object ImeDone : Command
    data object ImeCancel : Command

    data object GrantAllFiles : Command
}
