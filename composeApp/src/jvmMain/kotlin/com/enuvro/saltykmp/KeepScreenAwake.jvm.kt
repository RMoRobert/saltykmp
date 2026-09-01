package com.enuvro.saltykmp

import androidx.compose.runtime.Composable

/**
 * A no-op on the desktop.
 *
 * There is no cross-platform JVM way to hold off a screensaver — macOS wants an IOKit power assertion,
 * Windows `SetThreadExecutionState`, Linux whichever idle inhibitor the desktop happens to run — and
 * the alternative sometimes reached for, synthesising input with `java.awt.Robot`, moves the user's
 * real cursor. A laptop propped in a kitchen is also the least likely of the three to be left
 * untouched long enough to matter, so this stays honest rather than approximate.
 */
@Composable
internal actual fun KeepScreenAwake() {
}
