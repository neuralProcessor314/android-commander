package com.androidcommander.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

const val DEFAULT_PAGE_SIZE = 20
private const val ENTRY_FONT_FLOOR_SP = 8f
private const val ENTRY_FONT_CEILING_SP = 16f
private const val STRIPE_LINES = 4
private const val BAR_ROWS = 2
private const val PANE_CHROME_LINES_PER_PANE = 2 // header + footer

/** Comfortable minimum line length: enough for short fixed stripe messages plus margin. */
private const val MIN_USEFUL_COLUMNS = 32

data class LayoutMetrics(
    val entryFontSp: Float,
    val pageSize: Int,
    val barLabelFontSp: Float,
    val lineHeightPx: Float,
    /** Real measured character count that fits one full-width line at [entryFontSp]. */
    val columns: Int,
)

/**
 * Instruction section 5: PAGE_SIZE = 20 fixed at first layout; if 2x20 rows + chrome + the 2x8
 * bar can't fit at the 8sp floor, shrink PAGE_SIZE to whatever does fit exactly, then hold both
 * constant for the process lifetime. Computed once by measurement, never by scrolling/clipping.
 *
 * Font size is chosen to fit vertically (PAGE_SIZE rows x 2 panes + stripe + bar), but that alone
 * can pick a size too *wide* for the screen -- a portrait phone can satisfy the height budget at
 * a size where an ordinary stripe message no longer fits and visibly clips. So the height-derived
 * size is also capped by a minimum-useful-width check, shrinking further (down to the same floor)
 * if needed.
 */
fun computeLayoutMetrics(
    measurer: TextMeasurer,
    density: Density,
    availableHeightPx: Float,
    availableWidthPx: Float,
): LayoutMetrics {
    fun lineHeightAt(fontSizeSp: Float): Float {
        val result = measurer.measure("Mg", TextStyle(fontSize = fontSizeSp.sp, fontFamily = AcMono))
        return result.size.height.toFloat()
    }

    fun widthAt(fontSizeSp: Float, chars: Int): Float {
        val sample = "M".repeat(chars)
        return measurer.measure(sample, TextStyle(fontSize = fontSizeSp.sp, fontFamily = AcMono)).size.width.toFloat()
    }

    fun totalHeight(sp: Float, pageSize: Int): Float {
        val lh = lineHeightAt(sp)
        val paneLines = (pageSize + PANE_CHROME_LINES_PER_PANE) * 2 // two panes
        val stripeLines = STRIPE_LINES
        val barLines = BAR_ROWS
        return lh * (paneLines + stripeLines + barLines)
    }

    // Pick the largest entry font size in [FLOOR, CEILING] that fits PAGE_SIZE = 20 vertically.
    var chosenSp = ENTRY_FONT_FLOOR_SP
    var fitAtDefault = false
    var sp = ENTRY_FONT_CEILING_SP
    while (sp >= ENTRY_FONT_FLOOR_SP) {
        if (totalHeight(sp, DEFAULT_PAGE_SIZE) <= availableHeightPx) {
            chosenSp = sp
            fitAtDefault = true
            break
        }
        sp -= 0.5f
    }

    var pageSize = DEFAULT_PAGE_SIZE
    if (!fitAtDefault) {
        chosenSp = ENTRY_FONT_FLOOR_SP
        pageSize = DEFAULT_PAGE_SIZE
        while (pageSize > 1 && totalHeight(chosenSp, pageSize) > availableHeightPx) {
            pageSize--
        }
    }

    // Now cap by width: shrink further if MIN_USEFUL_COLUMNS doesn't fit at chosenSp.
    while (chosenSp > ENTRY_FONT_FLOOR_SP && widthAt(chosenSp, MIN_USEFUL_COLUMNS) > availableWidthPx) {
        chosenSp -= 0.5f
    }

    // Real measured column count at the final size, used for path ellipsising everywhere --
    // replaces any separate width/height-ratio guess.
    var columns = 1
    while (widthAt(chosenSp, columns + 1) <= availableWidthPx) columns++

    // Bar label size: largest size where a 4-char label + 1 blank column either side fits
    // one eighth of the screen width (instruction section 6).
    val cellWidth = availableWidthPx / 8f
    var barSp = ENTRY_FONT_CEILING_SP
    while (barSp > ENTRY_FONT_FLOOR_SP) {
        val w = measurer.measure(" MMMM ", TextStyle(fontSize = barSp.sp, fontFamily = AcMono)).size.width
        if (w <= cellWidth) break
        barSp -= 0.5f
    }

    return LayoutMetrics(
        entryFontSp = chosenSp,
        pageSize = pageSize,
        barLabelFontSp = barSp,
        lineHeightPx = lineHeightAt(chosenSp),
        columns = columns,
    )
}
