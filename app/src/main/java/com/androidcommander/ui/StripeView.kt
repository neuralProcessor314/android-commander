package com.androidcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidcommander.model.Command
import com.androidcommander.model.StripeState
import com.androidcommander.util.ellipsiseHead
import kotlinx.coroutines.delay

/**
 * Fixed 4-line stripe (instruction section 5/9). Height is hard-fixed from the monospace line
 * height x 4; idle lines pad with '-'. This is the only place questions, errors, and progress
 * ever render -- no dialogs, no toasts, anywhere in the app (section 0.2).
 */
@Composable
fun StripeView(
    stripe: StripeState,
    metrics: LayoutMetrics,
    pathColumns: Int,
    hasAllFilesAccess: Boolean,
    dispatch: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(fontFamily = AcMono, fontSize = metrics.entryFontSp.sp, color = AcWhite)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val wantsIme = stripe is StripeState.Input || stripe is StripeState.RenameEditor ||
        (stripe is StripeState.Confirm && stripe.options.size > 2)

    LaunchedEffect(wantsIme) {
        if (wantsIme) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    val (tag, line2, srcLine, dstLine) = stripeLines(stripe, pathColumns, hasAllFilesAccess)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AcBlack)
            // Horizontal only: the parent's fixed height is exactly 4 x lineHeight (AppScreen.kt)
            // with no allowance for padding, so any vertical padding here would overflow that
            // box by the padding amount and could push/clip a line -- possibly what was reported
            // as the third stripe line not showing up during RENM.
            .padding(horizontal = 4.dp),
    ) {
        StripeLine(tag, style)
        StripeLine(line2, style, cursor = stripe is StripeState.RenameEditor)
        StripeLine(srcLine, style)
        StripeLine(dstLine, style)
    }

    // Invisible IME host: RenameEditor keeps the keyboard open for the whole mode (section 8);
    // Input (MKDR) and multi-option Confirm prompts borrow the same mechanism. A *zero*-sized
    // focus-requested BasicTextField reliably crashes Compose's focus/IME-positioning machinery
    // (degenerate zero-area focus target) -- 1.dp is small enough to be visually negligible
    // against the stripe's fixed geometry but keeps layout/focus resolution well-defined.
    if (wantsIme) {
        val buffer = when (stripe) {
            is StripeState.Input -> stripe.buffer
            is StripeState.RenameEditor -> stripe.buffer
            else -> ""
        }
        var digitScratch by remember(stripe) { mutableStateOf("") }
        Box(modifier = Modifier.size(1.dp)) {
            BasicTextField(
                value = if (stripe is StripeState.Confirm) digitScratch else buffer,
                onValueChange = { new ->
                    if (stripe is StripeState.Confirm) {
                        val digit = new.lastOrNull()
                        if (digit != null && digit.isDigit()) dispatch(Command.ImeDigit(digit))
                        digitScratch = ""
                    } else {
                        dispatch(Command.ImeText(new))
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (stripe is StripeState.Confirm) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { dispatch(Command.ImeDone) }),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .size(1.dp),
                textStyle = style.copy(color = Color.Transparent),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
            )
        }
    }
}

private data class StripeLines(val tag: String, val line2: String, val src: String, val dst: String)

private fun stripeLines(stripe: StripeState, cols: Int, hasAllFilesAccess: Boolean): StripeLines = when (stripe) {
    StripeState.Idle -> if (!hasAllFilesAccess) {
        StripeLines("IDLE", "NO ALL-FILES ACCESS — PRESS OK TO GRANT", "-".repeat(cols), "-".repeat(cols))
    } else {
        StripeLines("IDLE", "-".repeat(cols), "-".repeat(cols), "-".repeat(cols))
    }
    is StripeState.Confirm -> StripeLines(
        "CONFIRM",
        stripe.question,
        ellipsiseHead(stripe.src, cols),
        ellipsiseHead(stripe.dst, cols),
    )
    is StripeState.Input -> StripeLines(
        "INPUT",
        "${stripe.label} ${stripe.buffer}",
        ellipsiseHead(stripe.src, cols),
        ellipsiseHead(stripe.dstPreview, cols),
    )
    is StripeState.RenameEditor -> StripeLines(
        "INPUT",
        "Rename ${stripe.currentName} to (${stripe.index}/${stripe.total}): ${stripe.buffer}_",
        ellipsiseHead(stripe.srcPath, cols),
        ellipsiseHead(stripe.dstPreview, cols),
    )
    is StripeState.Busy -> StripeLines(
        "BUSY",
        "${stripe.verb} ${stripe.done}/${stripe.total}",
        ellipsiseHead(stripe.current, cols),
        "-".repeat(cols),
    )
    is StripeState.Result -> StripeLines(
        "RESULT",
        stripe.summary,
        stripe.errors.getOrElse(0) { "-".repeat(cols) }.let { ellipsiseHead(it, cols) },
        // A short fixed label: never force-truncate it to an estimated column count, which was
        // cutting it off mid-word when the real available width didn't match the estimate.
        "OK TO CONTINUE",
    )
}

@Composable
private fun StripeLine(text: String, style: TextStyle, cursor: Boolean = false) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(cursor) {
        if (!cursor) return@LaunchedEffect
        while (true) {
            delay(500)
            blink = !blink
        }
    }
    val shown = if (cursor && !blink) text.dropLast(1) + " " else text
    androidx.compose.material3.Text(
        text = shown,
        style = style,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}
