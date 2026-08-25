package com.enuvro.saltykmp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/**
 * Draws a draggable scrollbar down the trailing edge of the enclosing [BoxScope], where the platform's
 * users expect one.
 *
 * Compose's scrollable containers are mobile-first: they show no persistent scrollbar, and a desktop
 * window offers no other clue that a long recipe or list continues below the fold. Compose does ship a
 * scrollbar, but only in its desktop source set (`Scrollbar.desktop.kt`) — hence expect/actual rather
 * than one shared composable. Android and iOS keep their own fading indicators, so there they're no-ops.
 */
@Composable
expect fun BoxScope.EdgeScrollbar(state: ScrollState)

/** [EdgeScrollbar] for a `LazyColumn`, which tracks its position in a [LazyListState] instead. */
@Composable
expect fun BoxScope.EdgeScrollbar(state: LazyListState)
