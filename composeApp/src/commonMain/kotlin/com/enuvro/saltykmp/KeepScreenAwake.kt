package com.enuvro.saltykmp

import androidx.compose.runtime.Composable

/**
 * Asks the platform not to dim or lock the screen while the calling composable is on screen, and hands
 * the display back the moment it leaves.
 *
 * Only [ChefScreen] calls this. A screen that goes dark four steps into a recipe is the defining
 * annoyance of cooking from a tablet, and it is the one screen in this app where the user is standing
 * across the room with their hands full — everywhere else the normal timeout is correct. Both sibling
 * apps do the same thing (the Swift app's `ScreenSleepBlocker`, the web app's Screen Wake Lock).
 *
 * Best-effort and silent by design: a platform that can't promise this simply doesn't, and that is not
 * worth interrupting someone mid-recipe about.
 */
@Composable
internal expect fun KeepScreenAwake()
