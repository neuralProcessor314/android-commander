package com.androidcommander.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.androidcommander.AppState
import com.androidcommander.model.Command
import com.androidcommander.model.PaneId
import com.androidcommander.model.StripeState

/**
 * Root layout (instruction section 5): stripe (fixed 4 lines) + two panes splitting the rest
 * 50/50 + a fixed 2x8 bottom bar. Composition is identical at every instant -- state changes
 * only ever change text inside a box, never the box itself, with rename mode's blanking as the
 * sole exception (section 8).
 */
@Composable
fun AppScreen(
    state: AppState,
    dispatch: (Command) -> Unit,
    onMetricsReady: (LayoutMetrics) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(AcBlack)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Computed exactly once and held for the process lifetime (instruction section 5) --
        // deliberately keyed on Unit, not on width/height, so a later resize never reflows it.
        val metrics = remember(Unit) {
            computeLayoutMetrics(measurer, density, heightPx, widthPx).also(onMetricsReady)
        }

        val stripeHeightDp = with(density) { (metrics.lineHeightPx * 4).toDp() }
        val blanked = state.stripe is StripeState.RenameEditor

        BackHandler(enabled = true) {
            if (state.stripe is StripeState.Input || state.stripe is StripeState.RenameEditor) {
                dispatch(Command.ImeCancel)
            } else {
                dispatch(Command.Cancel)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Tab -> { dispatch(Command.Tab); true }
                        Key.PageUp -> { dispatch(Command.Prev); true }
                        Key.PageDown -> { dispatch(Command.Next); true }
                        Key.Enter, Key.NumPadEnter -> { dispatch(Command.Ok); true }
                        // Key.Back is deliberately not handled here -- BackHandler above already
                        // owns the system back button/gesture; handling both would double-fire.
                        Key.Escape -> { dispatch(Command.Cancel); true }
                        Key.F5 -> { dispatch(Command.Copy); true }
                        Key.F6 -> { dispatch(Command.Move); true }
                        Key.F7 -> { dispatch(Command.Mkdir); true }
                        Key.F8 -> { dispatch(Command.Delete); true }
                        Key.F11 -> { dispatch(Command.Exit); true }
                        else -> false
                    }
                },
        ) {
            StripeView(
                stripe = state.stripe,
                metrics = metrics,
                pathColumns = metrics.columns,
                hasAllFilesAccess = state.hasAllFilesAccess,
                dispatch = dispatch,
                modifier = Modifier.height(stripeHeightDp),
            )

            if (blanked) {
                Column(modifier = Modifier.weight(1f).fillMaxSize().background(AcBlack)) {}
            } else {
                PaneView(
                    pane = state.paneA,
                    isActive = state.active == PaneId.A,
                    metrics = metrics,
                    pathColumns = metrics.columns,
                    dispatch = dispatch,
                    modifier = Modifier.weight(1f),
                )
                PaneView(
                    pane = state.paneB,
                    isActive = state.active == PaneId.B,
                    metrics = metrics,
                    pathColumns = metrics.columns,
                    dispatch = dispatch,
                    modifier = Modifier.weight(1f),
                )
            }

            BottomBar(blanked = blanked, metrics = metrics, dispatch = dispatch)
        }
    }
}
