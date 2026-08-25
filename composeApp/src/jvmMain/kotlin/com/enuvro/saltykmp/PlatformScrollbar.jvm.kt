package com.enuvro.saltykmp

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Desktop is the platform that needs the affordance: a real, draggable scrollbar hugging the edge. */
@Composable
actual fun BoxScope.EdgeScrollbar(state: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = edgeModifier(),
        style = saltyScrollbarStyle(),
    )
}

@Composable
actual fun BoxScope.EdgeScrollbar(state: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = edgeModifier(),
        style = saltyScrollbarStyle(),
    )
}

@Composable
private fun BoxScope.edgeModifier(): Modifier =
    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp)

/**
 * Compose's default scrollbar is a translucent *black*, which vanishes against a dark surface. Tint it
 * with the theme's onSurface instead so it reads in both palettes.
 */
@Composable
private fun saltyScrollbarStyle(): ScrollbarStyle {
    val ink = MaterialTheme.colorScheme.onSurface
    return LocalScrollbarStyle.current.copy(
        unhoverColor = ink.copy(alpha = 0.22f),
        hoverColor = ink.copy(alpha = 0.50f),
    )
}
