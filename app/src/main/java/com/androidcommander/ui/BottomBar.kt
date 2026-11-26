package com.androidcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidcommander.model.Command

/**
 * Fixed 2x8 grid of 16 cells (instruction section 5/7.3), all filled, never scrolled or hidden,
 * unaffected by IME insets. Bare verb labels, <=4 chars, no F-key numbers drawn (section 6).
 */
private val ROW1: List<Pair<String, Command>> = listOf(
    "TAB" to Command.Tab,
    "SWAP" to Command.Swap,
    "PREV" to Command.Prev,
    "NEXT" to Command.Next,
    "UP" to Command.Up,
    "EXPL" to Command.Explore,
    "ALL" to Command.SelectAll,
    "NONE" to Command.SelectNone,
)

private val ROW2: List<Pair<String, Command>> = listOf(
    "COPY" to Command.Copy,
    "MOVE" to Command.Move,
    "RENM" to Command.Rename,
    "MKDR" to Command.Mkdir,
    "DEL" to Command.Delete,
    "OK" to Command.Ok,
    "CNCL" to Command.Cancel,
    "EXIT" to Command.Exit,
)

@Composable
fun BottomBar(
    blanked: Boolean,
    metrics: LayoutMetrics,
    dispatch: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(AcBlack)) {
        BarRow(ROW1, blanked, metrics, dispatch)
        BarRow(ROW2, blanked, metrics, dispatch)
    }
}

@Composable
private fun BarRow(
    cells: List<Pair<String, Command>>,
    blanked: Boolean,
    metrics: LayoutMetrics,
    dispatch: (Command) -> Unit,
) {
    val style = TextStyle(fontFamily = AcMono, fontSize = metrics.barLabelFontSp.sp, color = AcWhite)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicBarRowHeight)) {
        cells.forEach { (label, cmd) ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AcBlack)
                    .then(if (blanked) Modifier else Modifier.fireOnDown { dispatch(cmd) }),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                if (!blanked) {
                    Text(text = label, style = style, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

private val IntrinsicBarRowHeight = 40.dp
