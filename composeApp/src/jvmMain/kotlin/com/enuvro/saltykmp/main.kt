package com.enuvro.saltykmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.createKeyValueStore
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

fun main() {
    // Without this the macOS application menu (and the Dock tile when run from Gradle) is labelled with
    // the main class — "MainKt". Must be set before AWT initialises.
    System.setProperty("apple.awt.application.name", "Salty")
    FileKit.init(appId = "Salty") // enables the desktop file picker
    runApp()
}

/**
 * Opens wide enough for the full sidebar plus a two-pane split (see [WidthClass]) rather than the
 * 800×600 Compose default, which would start the desktop app in the phone layout.
 */
private val DEFAULT_WINDOW_SIZE = DpSize(1280.dp, 860.dp)

private const val WINDOW_STATE_KEY = "desktopWindowState"

private fun isMac() = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** ⌘-key on macOS, Ctrl elsewhere. */
private fun accelerator(key: Key, shift: Boolean = false): KeyShortcut =
    if (isMac()) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)

@OptIn(FlowPreview::class)
private fun runApp() = application {
    val store = remember { createKeyValueStore() }
    val restored = remember { readWindowState(store) }
    val windowState = rememberWindowState(
        size = restored.size,
        position = restored.position,
        placement = restored.placement,
    )
    // Reopen where the user left the window. Debounced because dragging or resizing emits continuously
    // and every write goes through java.util.prefs.
    LaunchedEffect(windowState) {
        snapshotFlow { encodeWindowState(windowState) }
            .distinctUntilChanged()
            .debounce(500)
            .collect { store.putString(WINDOW_STATE_KEY, it) }
    }

    val commands = remember { AppCommands() }
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Salty",
    ) {
        SaltyMenuBar(commands)
        App(commands)
    }
}

/**
 * The macOS/Windows menu bar. Everything here is a shortcut into the running app via [AppCommands] —
 * the Swift app has these as real menu commands, and on desktop a menu bar is where people look for
 * "new", "find" and "sync" before they look at a floating action button.
 */
@Composable
private fun FrameWindowScope.SaltyMenuBar(commands: AppCommands) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("New Recipe", shortcut = accelerator(Key.N)) { commands.send(AppCommand.NewRecipe) }
            Item("Import from Web…", shortcut = accelerator(Key.I)) { commands.send(AppCommand.ImportFromWeb) }
            Separator()
            Item("Sync Now", shortcut = accelerator(Key.R)) { commands.send(AppCommand.SyncNow) }
            Separator()
            Item("Settings…", shortcut = accelerator(Key.Comma)) { commands.send(AppCommand.OpenSettings) }
        }
        Menu("Edit", mnemonic = 'E') {
            Item("Find in List", shortcut = accelerator(Key.F)) { commands.send(AppCommand.FindInList) }
        }
        Menu("Go", mnemonic = 'G') {
            Item("All Recipes", shortcut = accelerator(Key.One)) { commands.send(AppCommand.ShowAllRecipes) }
            Item("Favorites", shortcut = accelerator(Key.Two)) { commands.send(AppCommand.ShowFavorites) }
            Item("Want to Make", shortcut = accelerator(Key.Three)) { commands.send(AppCommand.ShowWantToMake) }
            Item("Shopping Lists", shortcut = accelerator(Key.Four)) { commands.send(AppCommand.ShowShoppingLists) }
        }
    }
}

/** The window geometry worth restoring: where a floating window sat, plus whether it was maximized. */
private class RestoredWindow(
    val size: DpSize,
    val position: WindowPosition,
    val placement: WindowPlacement,
)

/**
 * Serialized as `placement|width|height|x|y`, with the size/position always those of the *floating*
 * window — restoring a maximized window's bounds as its floating size would leave a screen-filling
 * window behind the moment the user un-maximizes it.
 */
private fun encodeWindowState(state: WindowState): String {
    val position = state.position
    val floating = state.placement == WindowPlacement.Floating
    // While maximized or full-screen, keep whatever floating bounds are currently reported; they are the
    // ones the platform will restore to anyway.
    val x = (position as? WindowPosition.Absolute)?.x?.value ?: Float.NaN
    val y = (position as? WindowPosition.Absolute)?.y?.value ?: Float.NaN
    return listOf(
        if (floating) WindowPlacement.Floating.name else state.placement.name,
        state.size.width.value,
        state.size.height.value,
        x,
        y,
    ).joinToString("|")
}

private fun readWindowState(store: KeyValueStore): RestoredWindow {
    val parts = store.getString(WINDOW_STATE_KEY, "").split('|')
    val placement = parts.getOrNull(0)
        ?.let { name -> WindowPlacement.entries.firstOrNull { it.name == name } }
        ?: WindowPlacement.Floating
    val width = parts.getOrNull(1)?.toFloatOrNull()
    val height = parts.getOrNull(2)?.toFloatOrNull()
    val x = parts.getOrNull(3)?.toFloatOrNull()
    val y = parts.getOrNull(4)?.toFloatOrNull()
    // A stored size below the phone breakpoint is honoured (the user may want it), but a zero/garbage
    // one is not — that would open an unusable window with no way back except clearing preferences.
    val size = if (width != null && height != null && width >= 320f && height >= 320f) {
        DpSize(width.dp, height.dp)
    } else {
        DEFAULT_WINDOW_SIZE
    }
    val position = if (x != null && y != null && !x.isNaN() && !y.isNaN()) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition.PlatformDefault
    }
    return RestoredWindow(size, position, placement)
}
