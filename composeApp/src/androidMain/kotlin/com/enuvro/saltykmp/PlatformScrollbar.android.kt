package com.enuvro.saltykmp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/** Touch platforms draw their own fading scroll indicator; a permanent bar would only steal width. */
@Composable
actual fun BoxScope.EdgeScrollbar(state: ScrollState) = Unit

@Composable
actual fun BoxScope.EdgeScrollbar(state: LazyListState) = Unit
