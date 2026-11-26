package com.androidcommander.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instruction section 11, tests 10-11: every button fires on ACTION_DOWN alone (no
 * ACTION_UP required), and rapid taps are all processed in order, none dropped or coalesced.
 * Exercises [fireOnDown] directly rather than the full app, since that's the one modifier
 * every button and entry row in the app is built on (PaneView.kt, BottomBar.kt).
 */
class TouchDownDispatchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firesOnDownAloneWithNoUpEvent() {
        var count = 0
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("target")
                    .fireOnDown { count++ },
            )
        }

        compose.onNodeWithTag("target").performTouchInput {
            down(center)
            // deliberately no up(): the command must already have fired.
        }

        assertEquals(1, count)
    }

    @Test
    fun thirtyRapidTapsProduceThirtyOrderedCommandsNoneDropped() {
        val seen = CopyOnWriteArrayList<Int>()
        var next = 0
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("target")
                    .fireOnDown { seen.add(next++) },
            )
        }

        repeat(30) {
            compose.onNodeWithTag("target").performTouchInput {
                down(center)
                up()
            }
        }

        assertEquals(30, seen.size)
        assertEquals((0 until 30).toList(), seen)
    }
}
