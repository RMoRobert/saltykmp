package com.enuvro.saltykmp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * The one platform that has a native text input to defer to.
 *
 * `PlatformImeOptions` is still `@ExperimentalComposeUiApi` in Compose Multiplatform 1.12, so the
 * opt-in is confined to this file rather than spread across the ~25 text fields that read the value.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual val nativeTextInputImeOptions: PlatformImeOptions? =
    PlatformImeOptions { usingNativeTextInput(true) }
