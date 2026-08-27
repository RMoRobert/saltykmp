package com.enuvro.saltykmp

/**
 * Desktop is a pointer-first platform, so it opens compact.
 *
 * This one value is the undo: switch it to [UiDensity.Comfortable] and the app is back to stock Material
 * metrics everywhere, with no other edit and no dead code left behind — [UiDensity.Compact] simply stops
 * being anyone's default, and a user who had explicitly chosen it in Settings still gets it.
 */
internal actual val platformDefaultDensity: UiDensity = UiDensity.Compact

/** Desktop machines include touchscreen laptops and Surfaces, so desktop is where the choice is offered. */
internal actual val uiDensityChoiceSupported: Boolean = true
