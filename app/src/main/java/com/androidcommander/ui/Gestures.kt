package com.androidcommander.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * Fires on touch-down alone (instruction section 7.1) -- not [androidx.compose.foundation.clickable],
 * which waits for release and applies slop/ripple. This app has no long-press affordance anywhere
 * (instruction section 0.4/7.1); this modifier never gates on time or distance.
 */
fun Modifier.fireOnDown(onDown: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onDown()
    }
}

private val PAGE_TURN_THRESHOLD: Dp = 48.dp

/**
 * A discrete page turn the instant a vertical drag passes a fixed distance -- no velocity, no
 * fling, no animation (instruction section 7.2). Also focuses the pane being dragged.
 */
fun Modifier.swipePageTurn(onFocus: () -> Unit, onPageTurn: (forward: Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        val thresholdPx = PAGE_TURN_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            onFocus()
            var accumulated = 0f
            var fired = false
            var pointerId = down.id
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break
                val dy = change.position.y - change.previousPosition.y
                accumulated += dy
                if (!fired && accumulated.absoluteValue >= thresholdPx) {
                    onPageTurn(accumulated > 0) // down-swipe (positive dy) = next page
                    fired = true
                }
                pointerId = change.id
            }
        }
    }
