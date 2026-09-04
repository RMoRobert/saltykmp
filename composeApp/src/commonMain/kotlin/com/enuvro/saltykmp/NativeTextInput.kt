package com.enuvro.saltykmp

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * iOS's own text-editing behaviour, requested per text field.
 *
 * Compose draws and drives its own caret, selection and context menu on every platform, which on iOS
 * reads as subtly foreign: the caret does not jump to a tap the way UIKit's does, there is no
 * magnifier, double- and triple-tap select nothing, and the edit menu is missing the entries iOS users
 * reach for (Translate, Look Up, Share) along with autocorrect and one-field-at-a-time password
 * autofill. Compose Multiplatform 1.11 added an opt-in that hands all of that back to UIKit; 1.12 is
 * the first release this project is on that has it.
 *
 * It is deliberately a `PlatformImeOptions?` rather than a Boolean: the flag only ever reaches a text
 * field through `KeyboardOptions.platformImeOptions`, and null is exactly what that parameter wants on
 * the platforms with nothing to say. See [SaltyKeyboardOptions] for the form nearly every call site
 * uses.
 *
 * To back the feature out, return null from the iOS actual — every text field then falls back to
 * Compose's own editing, and no call site changes.
 */
internal expect val nativeTextInputImeOptions: PlatformImeOptions?

/**
 * What a Salty text field passes when it has no keyboard requirements of its own: nothing configured
 * except [nativeTextInputImeOptions], which is a no-op off iOS.
 *
 * Fields that *do* have requirements (a URL keyboard, a Next action) build their own `KeyboardOptions`
 * and pass `platformImeOptions = nativeTextInputImeOptions` alongside them.
 */
internal val SaltyKeyboardOptions: KeyboardOptions =
    KeyboardOptions(platformImeOptions = nativeTextInputImeOptions)
