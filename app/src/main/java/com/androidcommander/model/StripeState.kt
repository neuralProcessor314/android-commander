package com.androidcommander.model

/**
 * One named answer to a [StripeState.Confirm] question. `key` is the digit ('1'..'9') that picks
 * it when more than two options exist (routed through the IME, see DECISIONS.md -- the bar only
 * has OK/CNCL, which map to `options[0]` and the option flagged `isCancel`).
 */
data class ConfirmOption(
    val key: Char,
    val label: String,
    val isCancel: Boolean = false,
)

/** Instruction section 9: exactly one of these owned by the operation queue at a time. */
sealed interface StripeState {
    data object Idle : StripeState

    data class Confirm(
        val question: String,
        val src: String = "",
        val dst: String = "",
        val options: List<ConfirmOption>,
    ) : StripeState

    data class Input(
        val label: String,
        val buffer: String,
        val src: String = "",
        val dstPreview: String = "",
    ) : StripeState

    data class Busy(
        val verb: String,
        val done: Int,
        val total: Int,
        val current: String,
    ) : StripeState

    data class Result(
        val summary: String,
        val errors: List<String>,
    ) : StripeState

    data class RenameEditor(
        val currentName: String,
        val index: Int,
        val total: Int,
        val buffer: String,
        val srcPath: String,
        val dstPreview: String,
    ) : StripeState
}
