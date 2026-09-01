package com.enuvro.saltykmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/** The idle timer is UIKit's version of this, and the same one the Swift app's `ScreenSleepBlocker` uses. */
@Composable
internal actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
