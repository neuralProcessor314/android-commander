package com.androidcommander.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.font.FontFamily

val AcBlack = Color(0xFF000000)
val AcWhite = Color(0xFFFFFFFF)
val AcMono = FontFamily.Monospace

/** Instruction section 0.3: no ripples anywhere -- every button fires via raw touch-down. */
private class NoIndicationNode : Modifier.Node(), DelegatableNode

private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Composable
fun AndroidCommanderTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        MaterialTheme(content = content)
    }
}
