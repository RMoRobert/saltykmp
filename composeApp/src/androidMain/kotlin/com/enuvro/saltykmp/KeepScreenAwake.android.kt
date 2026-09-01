package com.enuvro.saltykmp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * `FLAG_KEEP_SCREEN_ON` on the hosting activity's window — the flag Android provides for exactly this,
 * and one that needs no permission and is released with the window if the process dies holding it.
 */
@Composable
internal actual fun KeepScreenAwake() {
    val context = LocalContext.current
    DisposableEffect(context) {
        // The composition's context is usually the activity itself, but it can be a wrapper (a themed
        // context view, an inflated compose view); walk out to the activity rather than assuming.
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
