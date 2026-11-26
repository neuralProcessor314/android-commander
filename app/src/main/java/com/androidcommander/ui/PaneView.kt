package com.androidcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidcommander.model.Command
import com.androidcommander.model.PaneId
import com.androidcommander.model.PaneRow
import com.androidcommander.model.PaneState
import com.androidcommander.model.selectionKey
import com.androidcommander.util.ellipsiseHead
import com.androidcommander.util.formatMtime
import com.androidcommander.util.formatSize

/**
 * One pane: fixed header, exactly PAGE_SIZE rows (blank-padded, never scrolled -- no scrollable
 * list composable anywhere per instruction section 5), fixed footer. Selection renders inverted.
 * No hairline between individual entry rows (deviation from the instruction's literal text --
 * see DECISIONS.md): descenders/underscores in filenames collided visually with a 1px line sitting
 * right under them. Hairlines are kept around the pane box and between header/list/footer.
 */
@Composable
fun PaneView(
    pane: PaneState,
    isActive: Boolean,
    metrics: LayoutMetrics,
    pathColumns: Int,
    dispatch: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryStyle = TextStyle(fontFamily = AcMono, fontSize = metrics.entryFontSp.sp, color = AcWhite)

    Column(modifier = modifier.fillMaxWidth().background(AcBlack)) {
        // header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fireOnDown { dispatch(Command.FocusPane(pane.id)) },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${pane.id.name}> ${ellipsiseHead(pane.path.path, pathColumns - 12)}",
                style = entryStyle,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${if (isActive) "[ACT]" else "  -  "} ${pane.page + 1}/${pane.pageCount(metrics.pageSize)}",
                style = entryStyle,
                maxLines = 1,
            )
        }
        Hairline()

        val rows = pane.rowsForPage(metrics.pageSize)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .swipePageTurn(
                    onFocus = { dispatch(Command.FocusPane(pane.id)) },
                    onPageTurn = { forward -> dispatch(Command.SwipePane(pane.id, forward)) },
                ),
        ) {
            rows.forEachIndexed { idx, row ->
                EntryRow(
                    row = row,
                    selected = row?.let { pane.selected.contains(it.selectionKey()) } ?: false,
                    style = entryStyle,
                    onDown = { dispatch(Command.TapEntry(pane.id, idx)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // footer
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${pane.entries.size} ITEMS   ${pane.selected.size} SEL   ${formatSize(pane.freeBytes)} FREE",
                style = entryStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EntryRow(
    row: PaneRow?,
    selected: Boolean,
    style: TextStyle,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) AcWhite else AcBlack
    val fg = if (selected) AcBlack else AcWhite
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .fireOnDown(onDown)
            .padding(horizontal = 2.dp),
    ) {
        when (row) {
            null -> Text(" ", style = style.copy(color = fg), maxLines = 1, modifier = Modifier.weight(1f))
            PaneRow.Parent -> Text("..", style = style.copy(color = fg), maxLines = 1, modifier = Modifier.weight(1f))
            is PaneRow.Entry -> {
                val e = row.entry
                val suffix = if (e.isDirectory) "/" else if (e.isSymlink) "@" else ""
                Text(
                    text = "${e.name}$suffix",
                    style = style.copy(color = fg),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (e.isDirectory) "<DIR>".padStart(6) else formatSize(e.sizeBytes),
                    style = style.copy(color = fg),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
                Text(
                    text = " " + formatMtime(e.mtimeMillis),
                    style = style.copy(color = fg),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AcWhite),
    )
}
