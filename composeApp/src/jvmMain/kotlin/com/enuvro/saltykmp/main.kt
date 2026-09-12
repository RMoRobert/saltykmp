package com.enuvro.saltykmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.ComposeWindow
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
import kotlinx.coroutines.flow.mapNotNull

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
private fun accelerator(key: Key, shift: Boolean = false, alt: Boolean = false): KeyShortcut =
    if (isMac()) {
        KeyShortcut(key, meta = true, shift = shift, alt = alt)
    } else {
        KeyShortcut(key, ctrl = true, shift = shift, alt = alt)
    }

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

    // Recipes open in windows of their own (File ▸ Open Recipe in New Window, and the recipe menus), by
    // id, in the order opened. Each window's frame is kept by id as well, so opening a recipe that
    // already has one brings that window forward rather than stacking a second copy on top of it.
    val recipeWindows = remember { mutableStateListOf<String>() }
    val recipeFrames = remember { mutableMapOf<String, ComposeWindow>() }
    var mainFrame by remember { mutableStateOf<ComposeWindow?>(null) }
    val commands = remember {
        AppCommands().apply {
            openRecipeWindow = { id ->
                val open = recipeFrames[id]
                when {
                    open != null -> open.toFront()
                    id !in recipeWindows -> recipeWindows += id
                }
            }
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Salty",
    ) {
        LaunchedEffect(window) { mainFrame = window }
        SaltyMenuBar(commands, openShownRecipe = true)
        App(commands)
    }
    for (id in recipeWindows) {
        key(id) {
            RecipeWindow(
                recipeId = id,
                commands = commands,
                onFrame = { frame -> if (frame != null) recipeFrames[id] = frame else recipeFrames.remove(id) },
                toMainWindow = { mainFrame?.toFront() },
                onClose = { recipeWindows.remove(id) },
            )
        }
    }
}

/** Opens roomy enough for the recipe's wide layout (a hero photo), with the reading column capped inside. */
private val RECIPE_WINDOW_SIZE = DpSize(760.dp, 820.dp)

/**
 * A recipe in a window of its own. Titled by the recipe, and kept current, so a rename shows in the title
 * bar and the Window menu; a deleted recipe keeps the name it last had.
 *
 * It has the same menu bar as the main window: on macOS the menu bar belongs to whichever window is in
 * front, and a recipe window shouldn't take ⌘N or ⌘F away. Those commands act on the main window, so
 * they bring it forward — except Sync Now, which has nothing to show there.
 */
@Composable
private fun RecipeWindow(
    recipeId: String,
    commands: AppCommands,
    onFrame: (ComposeWindow?) -> Unit,
    toMainWindow: () -> Unit,
    onClose: () -> Unit,
) {
    val module = remember { AppModule.shared() }
    val title by remember(recipeId) { module.repository.recipeFlow(recipeId).mapNotNull { it?.name?.ifBlank { null } } }
        .collectAsState(initial = remember(recipeId) { module.repository.recipe(recipeId)?.name?.ifBlank { null } ?: "Recipe" })
    // This window's own ⌘I: the dialog belongs to the recipe on screen here, not to the main window's.
    var infoRequest by remember { mutableStateOf(0) }
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(size = RECIPE_WINDOW_SIZE),
        title = title,
    ) {
        DisposableEffect(window) {
            onFrame(window)
            onDispose { onFrame(null) }
        }
        SaltyMenuBar(
            commands,
            send = { command ->
                if (command != AppCommand.SyncNow) toMainWindow()
                commands.send(command)
            },
            getInfo = { infoRequest++ },
            closeWindow = onClose,
        )
        RecipeWindowContent(
            recipeId,
            onShowInLibrary = { filter ->
                toMainWindow()
                commands.showInLibrary(filter)
            },
            infoRequest = infoRequest,
        )
    }
}

/**
 * The macOS/Windows menu bar. Everything here is a shortcut into the running app via [AppCommands] —
 * the Swift app has these as real menu commands, and on desktop a menu bar is where people look for
 * "new", "find" and "sync" before they look at a floating action button.
 *
 * [openShownRecipe] adds File ▸ Open Recipe in New Window and a Get Info that acts on whatever recipe the
 * main window is reading (⌘↩ and ⌘I, as in the Swift app). A recipe window passes its own [getInfo] and
 * [closeWindow] instead — closing the main window quits, as it always has.
 */
@Composable
private fun FrameWindowScope.SaltyMenuBar(
    commands: AppCommands,
    send: (AppCommand) -> Unit = commands::send,
    openShownRecipe: Boolean = false,
    getInfo: (() -> Unit)? = null,
    closeWindow: (() -> Unit)? = null,
) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("New Recipe", shortcut = accelerator(Key.N)) { send(AppCommand.NewRecipe) }
            // ⌥⌘N, as the Swift app's New Recipe from Web…, which leaves ⌘I where macOS expects it.
            Item("Import from Web…", shortcut = accelerator(Key.N, alt = true)) { send(AppCommand.ImportFromWeb) }
            Item("Import from File…") { send(AppCommand.ImportFromFile) }
            Separator()
            if (openShownRecipe) {
                val shown = commands.shownRecipeId
                Item("Open Recipe in New Window", enabled = shown != null, shortcut = accelerator(Key.Enter)) {
                    shown?.let { commands.openRecipeWindow?.invoke(it) }
                }
                // Acts on the recipe the main window is reading; a recipe window passes its own below.
                Item("Get Info", enabled = shown != null, shortcut = accelerator(Key.I)) { send(AppCommand.GetInfo) }
            }
            if (getInfo != null) {
                Item("Get Info", shortcut = accelerator(Key.I), onClick = getInfo)
            }
            if (closeWindow != null) {
                Item("Close Window", shortcut = accelerator(Key.W), onClick = closeWindow)
            }
            Separator()
            Item("Sync Now", shortcut = accelerator(Key.R)) { send(AppCommand.SyncNow) }
            Separator()
            Item("Settings…", shortcut = accelerator(Key.Comma)) { send(AppCommand.OpenSettings) }
        }
        Menu("Edit", mnemonic = 'E') {
            Item("Find in List", shortcut = accelerator(Key.F)) { send(AppCommand.FindInList) }
        }
        Menu("Go", mnemonic = 'G') {
            Item("All Recipes", shortcut = accelerator(Key.One)) { send(AppCommand.ShowAllRecipes) }
            Item("Favorites", shortcut = accelerator(Key.Two)) { send(AppCommand.ShowFavorites) }
            Item("Want to Make", shortcut = accelerator(Key.Three)) { send(AppCommand.ShowWantToMake) }
            Item("Shopping Lists", shortcut = accelerator(Key.Four)) { send(AppCommand.ShowShoppingLists) }
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
