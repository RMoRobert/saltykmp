package com.enuvro.saltykmp

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width bucket, on the Material 3 breakpoints. Each step up adds exactly one thing, so a window
 * dragged from phone-width to full-screen grows through them without any layout jumping two levels:
 *
 * | class    | width      | navigation           | panes |
 * |----------|------------|----------------------|-------|
 * | Compact  | < 600dp    | modal drawer         | one   |
 * | Medium   | 600–839dp  | rail (+ modal drawer)| one   |
 * | Expanded | 840–1199dp | rail (+ modal drawer)| two   |
 * | Large    | ≥ 1200dp   | permanent sidebar    | two   |
 *
 * A rail can't hold the Categories/Courses/Tags lists, so wherever the rail is used the modal drawer is
 * still reachable from its menu button — the classifiers are never stranded. The permanent sidebar starts
 * at 1200dp rather than 840dp because a 280dp sidebar plus a 340dp list pane leaves too little for the
 * recipe itself below that; this is what the Swift app's `NavigationSplitView` ends up doing on a Mac.
 */
internal enum class WidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ;

    /** List on the left, recipe (or shopping list) on the right, both visible at once. */
    val twoPane: Boolean get() = this == Expanded || this == Large

    /** The full drawer contents are always on screen; no menu button, no modal drawer. */
    val permanentSidebar: Boolean get() = this == Large

    /** A compact icon rail carries the fixed destinations; the drawer opens over it for the rest. */
    val navigationRail: Boolean get() = this == Medium || this == Expanded
}

internal fun widthClassFor(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 840.dp -> WidthClass.Medium
    width < 1200.dp -> WidthClass.Expanded
    else -> WidthClass.Large
}

/** Width of the list pane in a two-pane layout; the detail pane takes what's left. */
internal val LIST_PANE_WIDTH = 340.dp

/** Width of the permanent sidebar (narrower than the 360dp modal drawer, which is sized for phones). */
internal val SIDEBAR_WIDTH = 280.dp

/**
 * Caps for prose and for forms. A recipe stretched the full width of a 1600px window is unreadable, and
 * a row of text fields that wide is worse; both are centred within whatever space they're given, so on a
 * phone (where the cap is never reached) nothing changes.
 */
internal val READING_WIDTH = 860.dp
internal val FORM_WIDTH = 720.dp

/**
 * An action the host platform can fire at the running app — the desktop menu bar and its keyboard
 * shortcuts, today. Mobile has no equivalent surface and simply never sends one.
 */
enum class AppCommand {
    NewRecipe,
    ImportFromWeb,
    SyncNow,
    OpenSettings,
    ShowAllRecipes,
    ShowFavorites,
    ShowWantToMake,
    ShowShoppingLists,
    FindInList,
    Back,
}

/**
 * The channel those actions travel down. [App] installs its handler on composition and clears it on
 * dispose, so a menu item clicked before the UI exists (or after it's gone) is a no-op rather than a
 * crash. Create one in the platform entry point and hand it to both the menu bar and [App].
 */
class AppCommands {
    internal var handler: ((AppCommand) -> Unit)? = null

    fun send(command: AppCommand) {
        handler?.invoke(command)
    }
}
