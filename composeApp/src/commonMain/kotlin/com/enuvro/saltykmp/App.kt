package com.enuvro.saltykmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.Recipe
import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.di.currentLibraryDir
import com.enuvro.saltykmp.di.customLibraryLocationSupported
import com.enuvro.saltykmp.di.linkedFolderSyncSupported
import com.enuvro.saltykmp.search.RecipeSearch
import com.enuvro.saltykmp.search.RecipeSearchField
import com.enuvro.saltykmp.search.RecipeSearchFields
import com.enuvro.saltykmp.di.decodeImageBitmap
import com.enuvro.saltykmp.di.makeThumbnail
import com.enuvro.saltykmp.di.rememberCameraCapture
import com.enuvro.saltykmp.sync.LocalStore
import com.enuvro.saltykmp.sync.SyncResult
import com.enuvro.saltykmp.util.PreparedDates
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The three classifiers a recipe can be filed under. "Classifier" is this codebase's catch-all for the
 * set — the app has no single user-facing word for them, and calling them "the library" would wrongly
 * suggest the recipes themselves. Each is editable with identical (name) CRUD (mirrors the Swift app).
 */
internal enum class ClassifierKind(val title: String, val singular: String) {
    Courses("Courses", "Course"),
    Categories("Categories", "Category"),
    Tags("Tags", "Tag"),
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val id: String) : Screen
    /** [imported] seeds a brand-new recipe from the web importer; null for a blank new recipe or an edit. */
    data class Edit(val id: String?, val imported: ImportedRecipe? = null) : Screen
    data class ManageClassifier(val kind: ClassifierKind) : Screen
    data object ShoppingLists : Screen
    data class ShoppingListDetail(val id: String) : Screen
    data object Settings : Screen
}

// Material 3 scheme built from the brand azure (#0291FA). Roles are assigned by tonal value off three
// harmonized palettes — primary (azure), secondary (muted blue-gray), tertiary (sea-teal accent) — over
// cool neutrals, so secondary/tertiary/containers all relate to the chosen blue instead of clashing.
private val SaltyLightColors = lightColorScheme(
    primary = Color(0xFF0291FA),            // the selected brand blue, kept as-is
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D32),
    inversePrimary = Color(0xFF98CBFF),
    secondary = Color(0xFF4F616E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF006876),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA2EEFF),
    onTertiaryContainer = Color(0xFF001F25),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceTint = Color(0xFF0291FA),
    inverseSurface = Color(0xFF2F3133),
    inverseOnSurface = Color(0xFFF0F1F4),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFCFF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F9),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9),
)

// Dark theme on a deep navy base (same azure hue, low-tone neutrals tinted blue), per M3 dark roles:
// primary/secondary/tertiary use the light (~tone 80) ends of the palettes for legibility on navy.
private val SaltyDarkColors = darkColorScheme(
    primary = Color(0xFF98CBFF),
    onPrimary = Color(0xFF003353),
    primaryContainer = Color(0xFF004B70),
    onPrimaryContainer = Color(0xFFCFE5FF),
    inversePrimary = Color(0xFF0291FA),
    secondary = Color(0xFFB7C9D9),
    onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFF52D7EB),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF004E5A),
    onTertiaryContainer = Color(0xFFA2EEFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1722),
    onBackground = Color(0xFFDEE3EA),
    surface = Color(0xFF0D1722),
    onSurface = Color(0xFFDEE3EA),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = Color(0xFF98CBFF),
    inverseSurface = Color(0xFFDEE3EA),
    inverseOnSurface = Color(0xFF2C3137),
    outline = Color(0xFF8B9298),
    outlineVariant = Color(0xFF41484D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF33404D),
    surfaceDim = Color(0xFF0D1722),
    surfaceContainerLowest = Color(0xFF08111A),
    surfaceContainerLow = Color(0xFF15202B),
    surfaceContainer = Color(0xFF19232F),
    surfaceContainerHigh = Color(0xFF232E3A),
    surfaceContainerHighest = Color(0xFF2E3945),
)

@Composable
private fun SaltyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SaltyDarkColors else SaltyLightColors,
        content = content,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(commands: AppCommands? = null) {
    val module = remember { AppModule() }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    // Hoisted out of the list screen so it survives navigating into a recipe and back — and so a tag or
    // category chip on the detail screen can jump the list to that filter.
    var recipeFilter by remember { mutableStateOf<RecipeFilter>(RecipeFilter.All) }
    // App-level rather than inside the recipe list, so the desktop menu bar can start an import from
    // wherever the user happens to be.
    var showWebImport by remember { mutableStateOf(false) }
    // Bumped by the Find command; the recipe list opens its search field whenever this changes.
    var findRequest by remember { mutableStateOf(0) }
    var menuSyncRunning by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Reconcile the linked sync folder BEFORE any screen opens the database (a COPY_IN replaces the local
    // DB file, which is only safe while the DB is closed). Gate the UI until that completes.
    var startupPhase by remember { mutableStateOf(StartupPhase.Loading) }
    LaunchedEffect(Unit) {
        val result = runCatching { module.startup() }.getOrDefault(LibraryFolderSyncResult.ERROR)
        startupPhase = if (result == LibraryFolderSyncResult.CONFLICT) StartupPhase.Conflict else StartupPhase.Ready
    }
    // Diagnostic: once the DB is opened (post-reconcile/conflict-resolution), report how many recipes are
    // actually readable. count=0 → the opened DB is empty; an exception → the DB can't be read (schema).
    LaunchedEffect(startupPhase) {
        if (startupPhase == StartupPhase.Ready) {
            val count = runCatching { module.repository.debugRecipeCount() }
            println("LibraryFolderLink: DB opened — recipe count = ${count.getOrNull() ?: "READ FAILED: ${count.exceptionOrNull()?.message}"}")
        }
    }
    // Leaving the app is the natural moment to copy recent edits to the linked folder (no-op when unlinked or
    // unchanged). ON_STOP maps to the activity stopping on Android and the app backgrounding on iOS.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { module.onAppBackground() }

    /** Back out of the current sub-screen. A shopping list returns to the lists, not out to the recipes. */
    fun goBack() {
        screen = if (screen is Screen.ShoppingListDetail) Screen.ShoppingLists else Screen.List
    }

    /** Announce an outcome; replaces any showing snackbar so a fast second action isn't queued behind it. */
    fun notify(message: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }

    // Commands from the host platform (today: the desktop menu bar and its keyboard shortcuts). The handler
    // is installed for as long as the app is composed; anything sent outside that window is dropped rather
    // than crashing, which is what makes a menu item safe to click during startup.
    DisposableEffect(commands) {
        commands?.handler = handler@{ command ->
            when (command) {
                AppCommand.NewRecipe -> screen = Screen.Edit(null)
                AppCommand.ImportFromWeb -> showWebImport = true
                AppCommand.OpenSettings -> screen = Screen.Settings
                AppCommand.ShowAllRecipes -> { recipeFilter = RecipeFilter.All; screen = Screen.List }
                AppCommand.ShowFavorites -> { recipeFilter = RecipeFilter.Favorites; screen = Screen.List }
                AppCommand.ShowWantToMake -> { recipeFilter = RecipeFilter.WantToMake; screen = Screen.List }
                AppCommand.ShowShoppingLists -> screen = Screen.ShoppingLists
                // Find belongs to whichever list is on screen — recipes or shopping lists, both of which
                // watch this counter. On Settings or an editor there is nothing to search, so it's dropped.
                AppCommand.FindInList -> if (screen != Screen.Settings && screen !is Screen.ManageClassifier) {
                    findRequest++
                }
                AppCommand.Back -> goBack()
                AppCommand.SyncNow -> {
                    if (menuSyncRunning) return@handler
                    menuSyncRunning = true
                    scope.launch {
                        val message = try {
                            "Sync complete — " + module.sync().summary()
                        } catch (e: Throwable) {
                            "Sync failed: ${e.message}"
                        } finally {
                            menuSyncRunning = false
                        }
                        notify(message)
                    }
                }
            }
        }
        onDispose { commands?.handler = null }
    }

    SaltyTheme {
        when (startupPhase) {
            StartupPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            StartupPhase.Conflict -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Library changed in two places") },
                    text = {
                        Text(
                            "Both this device and your linked folder changed since the last sync. Keep one — " +
                                "the other copy will be overwritten.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { module.resolveConflictKeepingFolder() }
                                startupPhase = StartupPhase.Ready
                            }
                        }) { Text("Use folder’s copy") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { module.resolveConflictKeepingLocal() }
                                startupPhase = StartupPhase.Ready
                            }
                        }) { Text("Keep this device’s") }
                    },
                )
            }
            StartupPhase.Ready -> AppContent(
                module = module,
                screen = screen,
                onScreen = { screen = it },
                recipeFilter = recipeFilter,
                onRecipeFilter = { recipeFilter = it },
                snackbarHost = snackbarHost,
                onBack = { goBack() },
                findRequest = findRequest,
                onImportFromWeb = { showWebImport = true },
            )
        }
        if (showWebImport) {
            WebImportDialog(
                module,
                onDismiss = { showWebImport = false },
                onImported = { showWebImport = false; screen = Screen.Edit(null, it) },
            )
        }
    }
}

private enum class StartupPhase { Loading, Conflict, Ready }

/** User-facing message for a linked-folder sync outcome (Settings status line). */
private fun folderSyncMessage(result: LibraryFolderSyncResult): String = when (result) {
    LibraryFolderSyncResult.NOT_LINKED -> "No folder linked."
    LibraryFolderSyncResult.NO_CHANGE -> "Linked folder is already up to date."
    LibraryFolderSyncResult.PUSHED -> "Library copied to the linked folder."
    LibraryFolderSyncResult.PULLED -> "Loaded the newer library from the folder."
    LibraryFolderSyncResult.SEEDED -> "Linked folder initialized with your library."
    LibraryFolderSyncResult.FOLDER_NEWER -> "The folder has a newer library — it will be loaded the next time the app starts."
    LibraryFolderSyncResult.CONFLICT -> "Both this device and the folder changed — you'll be asked which to keep on next launch."
    LibraryFolderSyncResult.ERROR -> "Couldn't access the linked folder."
}

/**
 * Everything a screen needs from the app shell, in one object. The compact and wide layouts would
 * otherwise repeat the same nine parameters at every call site.
 */
private class AppShell(
    val module: AppModule,
    val screen: Screen,
    val filter: RecipeFilter,
    val findRequest: Int,
    val onScreen: (Screen) -> Unit,
    val onFilter: (RecipeFilter) -> Unit,
    val onBack: () -> Unit,
    val onImportFromWeb: () -> Unit,
    val showUndo: (message: String, undo: () -> Unit) -> Unit,
) {
    /** Show the library sliced by [f]; also leaves whatever sub-screen asked for it. */
    fun openFilter(f: RecipeFilter) {
        onFilter(f)
        onScreen(Screen.List)
    }

    /** True while the recipe list (or a recipe opened from it) is the active area. */
    val onRecipes: Boolean
        get() = screen is Screen.List || screen is Screen.Detail || screen is Screen.Edit
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppContent(
    module: AppModule,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    recipeFilter: RecipeFilter,
    onRecipeFilter: (RecipeFilter) -> Unit,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    findRequest: Int,
    onImportFromWeb: () -> Unit,
) {
    val snackbarScope = rememberCoroutineScope()

    /** Show [message] with an Undo action that runs [undo] if tapped. */
    fun showUndo(message: String, undo: () -> Unit) {
        snackbarScope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            val result = snackbarHost.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    val shell = AppShell(
        module = module,
        screen = screen,
        filter = recipeFilter,
        findRequest = findRequest,
        onScreen = onScreen,
        onFilter = onRecipeFilter,
        onBack = onBack,
        onImportFromWeb = onImportFromWeb,
        showUndo = ::showUndo,
    )

    Column(Modifier.fillMaxSize()) {
        // A persistent, dismissible banner when several auto-syncs in a row have failed (Swift app parity).
        val autoSyncFailing by module.autoSync.failing.collectAsState()
        if (autoSyncFailing) {
            AutoSyncFailureBanner(
                onClose = { module.autoSync.dismissBanner() },
                onPause = { module.autoSync.pauseForOneDay() },
            )
        }
        // System / gesture back returns to the list from any sub-screen.
        BackHandler(enabled = screen != Screen.List) { onBack() }
        // The active screen fills the space below the banner (each screen is its own fillMaxSize Scaffold).
        Box(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Everything adaptive keys off this one value: phone-width keeps the modal drawer and a
                // single screen at a time; wider windows get a rail, then two panes, then a full sidebar.
                when (val width = widthClassFor(maxWidth)) {
                    WidthClass.Compact -> CompactLayout(shell)
                    else -> WideLayout(shell, width)
                }
            }
            // Last child: on top for both drawing and hit-testing. App-level rather than per-screen because
            // the action that raises it (deleting a recipe) navigates away from the screen that triggered it.
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Phone layout: one screen at a time, navigation behind a modal drawer on the recipe list. */
@Composable
private fun CompactLayout(shell: AppShell) {
    when (val s = shell.screen) {
        Screen.List -> RecipeListScreen(shell)
        is Screen.Detail -> RecipeDetailScreen(
            shell.module, s.id,
            onBack = shell.onBack,
            onClose = shell.onBack,
            onEdit = { shell.onScreen(Screen.Edit(s.id)) },
            onFilter = { shell.openFilter(it) },
            onDeleted = shell.showUndo,
        )
        is Screen.Edit -> RecipeEditScreen(
            shell.module, s.id, s.imported,
            onDone = { savedId -> shell.onScreen(savedId?.let { Screen.Detail(it) } ?: Screen.List) },
        )
        is Screen.ManageClassifier -> ClassifierEditScreen(shell.module, s.kind, onBack = shell.onBack)
        Screen.ShoppingLists -> ShoppingListsScreen(
            shell.module,
            onOpen = { shell.onScreen(Screen.ShoppingListDetail(it)) },
            onBack = shell.onBack,
            findRequest = shell.findRequest,
        )
        is Screen.ShoppingListDetail -> ShoppingListDetailScreen(
            shell.module, s.id,
            // Back from a list returns to the lists, not all the way out to the recipes.
            onBack = shell.onBack,
            onClose = { shell.onScreen(Screen.ShoppingLists) },
            onUndoable = shell.showUndo,
        )
        Screen.Settings -> SettingsScreen(shell.module, onBack = shell.onBack)
    }
}

/**
 * Tablet / desktop layout. Navigation is always visible — an icon rail up to 1200dp, the full sidebar
 * above it — and from 840dp the list and what's selected in it sit side by side, the way the Swift app's
 * `NavigationSplitView` does on a Mac. Where the rail is used the modal drawer is still one tap away, so
 * Categories / Courses / Tags (which don't fit a rail) are never out of reach.
 */
@Composable
private fun WideLayout(shell: AppShell, width: WidthClass) {
    if (width.permanentSidebar) {
        Row(Modifier.fillMaxSize()) {
            PermanentDrawerSheet(Modifier.width(SIDEBAR_WIDTH)) { SaltyDrawerContents(shell) }
            VerticalDivider()
            Box(Modifier.weight(1f)) { WideContent(shell, width) }
        }
    } else {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet { SaltyDrawerContents(shell, onNavigated = { scope.launch { drawerState.close() } }) }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                SaltyNavigationRail(shell, onMenu = { scope.launch { drawerState.open() } })
                VerticalDivider()
                Box(Modifier.weight(1f)) { WideContent(shell, width) }
            }
        }
    }
}

/** The content area to the right of the rail / sidebar: one pane at medium widths, two from 840dp up. */
@Composable
private fun WideContent(shell: AppShell, width: WidthClass) {
    when (val s = shell.screen) {
        Screen.List, is Screen.Detail, is Screen.Edit -> if (width.twoPane) {
            Row(Modifier.fillMaxSize()) {
                RecipeListPane(
                    shell,
                    selectedId = (s as? Screen.Detail)?.id ?: (s as? Screen.Edit)?.id,
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                )
                VerticalDivider()
                Box(Modifier.weight(1f)) { RecipeDetailPane(shell, s) }
            }
        } else {
            when (s) {
                Screen.List -> RecipeListPane(shell, selectedId = null)
                is Screen.Detail -> RecipeDetailScreen(
                    shell.module, s.id,
                    onBack = shell.onBack,
                    onClose = shell.onBack,
                    onEdit = { shell.onScreen(Screen.Edit(s.id)) },
                    onFilter = { shell.openFilter(it) },
                    onDeleted = shell.showUndo,
                )
                is Screen.Edit -> RecipeEditScreen(
                    shell.module, s.id, s.imported,
                    onDone = { savedId -> shell.onScreen(savedId?.let { Screen.Detail(it) } ?: Screen.List) },
                )
            }
        }
        Screen.ShoppingLists, is Screen.ShoppingListDetail -> if (width.twoPane) {
            Row(Modifier.fillMaxSize()) {
                ShoppingListsScreen(
                    shell.module,
                    onOpen = { shell.onScreen(Screen.ShoppingListDetail(it)) },
                    onBack = null,
                    selectedId = (s as? Screen.ShoppingListDetail)?.id,
                    findRequest = shell.findRequest,
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                )
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    if (s is Screen.ShoppingListDetail) {
                        ShoppingListDetailScreen(
                            shell.module, s.id,
                            onBack = null,
                            onClose = { shell.onScreen(Screen.ShoppingLists) },
                            onUndoable = shell.showUndo,
                        )
                    } else {
                        PanePlaceholder(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            title = "No list selected",
                            body = "Pick a shopping list on the left, or start a new one.",
                        )
                    }
                }
            }
        } else {
            when (s) {
                Screen.ShoppingLists -> ShoppingListsScreen(
                    shell.module,
                    onOpen = { shell.onScreen(Screen.ShoppingListDetail(it)) },
                    onBack = null,
                    findRequest = shell.findRequest,
                )
                is Screen.ShoppingListDetail -> ShoppingListDetailScreen(
                    shell.module, s.id,
                    onBack = shell.onBack,
                    onClose = { shell.onScreen(Screen.ShoppingLists) },
                    onUndoable = shell.showUndo,
                )
            }
        }
        // Settings and the classifier editors are app-level, not a slice of the library, so they take the
        // whole content area (the navigation beside them stays put) rather than a detail pane.
        is Screen.ManageClassifier -> ClassifierEditScreen(shell.module, s.kind, onBack = shell.onBack)
        Screen.Settings -> SettingsScreen(shell.module, onBack = shell.onBack)
    }
}

/** Right-hand pane of the recipes split view: the open recipe, the editor, or a "pick one" placeholder. */
@Composable
private fun RecipeDetailPane(shell: AppShell, screen: Screen) {
    when (screen) {
        is Screen.Detail -> RecipeDetailScreen(
            shell.module, screen.id,
            // No back arrow in a split view: the list it would return to never went away.
            onBack = null,
            onClose = { shell.onScreen(Screen.List) },
            onEdit = { shell.onScreen(Screen.Edit(screen.id)) },
            onFilter = { shell.openFilter(it) },
            onDeleted = shell.showUndo,
            wide = true,
        )
        is Screen.Edit -> RecipeEditScreen(
            shell.module, screen.id, screen.imported,
            onDone = { savedId -> shell.onScreen(savedId?.let { Screen.Detail(it) } ?: Screen.List) },
        )
        else -> PanePlaceholder(
            icon = Icons.Outlined.Restaurant,
            title = "No recipe selected",
            body = "Choose a recipe on the left to read it here.",
        )
    }
}

/** Centered "nothing selected yet" filler for an empty detail pane. */
@Composable
private fun PanePlaceholder(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(icon = icon, title = title, body = body)
    }
}

/**
 * Icon rail for medium/expanded windows: the fixed destinations only. Categories / Courses / Tags are
 * lists of arbitrary length and can't live on a rail, so the menu button opens the full drawer over it.
 */
@Composable
private fun SaltyNavigationRail(shell: AppShell, onMenu: () -> Unit) {
    NavigationRail(
        header = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu")
            }
        },
    ) {
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.All,
            onClick = { shell.openFilter(RecipeFilter.All) },
            icon = { Icon(Icons.Outlined.Restaurant, contentDescription = null) },
            label = { Text("Recipes") },
        )
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.Favorites,
            onClick = { shell.openFilter(RecipeFilter.Favorites) },
            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
            label = { Text("Favorites") },
        )
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.WantToMake,
            onClick = { shell.openFilter(RecipeFilter.WantToMake) },
            icon = { Icon(Icons.Filled.BookmarkAdded, contentDescription = null) },
            label = { Text("To Make") },
        )
        NavigationRailItem(
            selected = shell.screen is Screen.ShoppingLists || shell.screen is Screen.ShoppingListDetail,
            onClick = { shell.onScreen(Screen.ShoppingLists) },
            icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
            label = { Text("Lists") },
        )
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = shell.screen is Screen.Settings,
            onClick = { shell.onScreen(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Shown at the top of the app when auto-sync has failed several times in a row. Easily dismissible: "Close"
 * hides it (it returns only after fresh failures), "Pause for a day" suppresses auto-sync for 24 hours.
 */
@Composable
private fun AutoSyncFailureBanner(onClose: () -> Unit, onPause: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Automatic sync isn’t working — the server keeps failing to respond.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPause) { Text("Pause for a day") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** Which slice of the library the list is currently showing (mirrors the Swift app's sidebar). */
private sealed interface RecipeFilter {
    val title: String

    data object All : RecipeFilter {
        override val title = "All Recipes"
    }

    data object Favorites : RecipeFilter {
        override val title = "Favorites"
    }

    data object WantToMake : RecipeFilter {
        override val title = "Want to Make"
    }

    data class Course(val id: String, val name: String) : RecipeFilter {
        override val title get() = name
    }

    data class Category(val id: String, val name: String) : RecipeFilter {
        override val title get() = name
    }

    data class Tag(val id: String, val name: String) : RecipeFilter {
        override val title get() = name
    }
}

/** Recipe-list sort field (mirrors the Swift app's sort options); applied in-memory over the list. */
private enum class RecipeSort(val label: String) {
    NAME("Name"),
    DATE_MODIFIED("Date Modified"),
    DATE_CREATED("Date Created"),
    SOURCE("Source"),
    RATING("Rating"),
    DIFFICULTY("Difficulty"),
    LAST_MADE("Last Made"),
}

/** Sort [list] by [sort]; ISO-8601 date strings compare chronologically, so plain string order works. */
private fun sortRecipes(list: List<Recipe>, sort: RecipeSort, ascending: Boolean): List<Recipe> {
    val key: Comparator<Recipe> = when (sort) {
        RecipeSort.NAME -> compareBy { it.name.lowercase() }
        RecipeSort.DATE_MODIFIED -> compareBy { it.lastModifiedDate ?: "" }
        RecipeSort.DATE_CREATED -> compareBy { it.createdDate ?: "" }
        RecipeSort.SOURCE -> compareBy { it.source?.lowercase() ?: "" }
        RecipeSort.RATING -> compareBy { it.rating?.rawValue ?: 0L }
        RecipeSort.DIFFICULTY -> compareBy { it.difficulty?.rawValue ?: 0L }
        RecipeSort.LAST_MADE -> compareBy { it.lastPrepared ?: "" }
    }
    val sorted = list.sortedWith(key.thenBy { it.name.lowercase() })
    val ordered = if (ascending) sorted else sorted.reversed()
    // Never-made recipes go LAST in both directions (the Swift app's ORDER BY does the same). Ascending
    // would otherwise open with every recipe that has no date at all — noise, for a sort that exists to
    // answer "what have I cooked lately".
    return if (sort == RecipeSort.LAST_MADE) {
        ordered.partition { !it.lastPrepared.isNullOrBlank() }.let { (made, never) -> made + never }
    } else {
        ordered
    }
}

/**
 * Phone-width recipe list: the pane plus the modal drawer it opens. Wider layouts render
 * [RecipeListPane] directly, with the navigation already on screen beside it.
 */
@Composable
private fun RecipeListScreen(shell: AppShell) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SaltyDrawerContents(shell, onNavigated = { scope.launch { drawerState.close() } })
            }
        },
    ) {
        RecipeListPane(
            shell,
            selectedId = null,
            navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                }
            },
        )
    }
}

/**
 * The recipe list itself: app bar (search / sort / overflow), the rows, and the new-recipe FAB.
 *
 * [selectedId] is set only in a two-pane layout, where the row for the recipe showing on the right is
 * highlighted; [navigationIcon] is the drawer button on layouts that have one and empty where the
 * navigation is already visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeListPane(
    shell: AppShell,
    selectedId: String?,
    navigationIcon: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val module = shell.module
    val filter = shell.filter
    var sort by remember {
        mutableStateOf(runCatching { RecipeSort.valueOf(module.settings.recipeSort) }.getOrDefault(RecipeSort.NAME))
    }
    var ascending by remember { mutableStateOf(module.settings.recipeSortAscending) }
    var sortMenu by remember { mutableStateOf(false) }
    // Search is opt-in: the field replaces the title while active, and closing it clears the query so the
    // list can never stay silently filtered by a query the user can't see.
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchOptionsMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var searchFields by remember { mutableStateOf(module.settings.searchFields) }

    // The desktop menu bar's Find command (⌘F) arrives as a bumped counter. Skipping 0 keeps the field
    // shut on first composition.
    LaunchedEffect(shell.findRequest) {
        if (shell.findRequest > 0) searchActive = true
    }

    val recipes by remember(filter) {
        when (val f = filter) {
            // Favorites / Want to Make are flags on the recipe row rather than their own query; they read
            // the full list and narrow it below, the way the Swift sidebar's scope + forced flag does.
            RecipeFilter.All, RecipeFilter.Favorites, RecipeFilter.WantToMake -> module.repository.recipes()
            is RecipeFilter.Course -> module.repository.recipesForCourse(f.id)
            is RecipeFilter.Category -> module.repository.recipesForCategory(f.id)
            is RecipeFilter.Tag -> module.repository.recipesForTag(f.id)
        }
    }.collectAsState(initial = emptyList())
    val courses by module.repository.courses().collectAsState(initial = emptyList())
    val categoryNamesByRecipe by module.repository.categoryNamesByRecipe().collectAsState(initial = emptyMap())
    val tagNamesByRecipe by module.repository.tagNamesByRecipe().collectAsState(initial = emptyMap())

    val flagged = remember(recipes, filter) {
        when (filter) {
            RecipeFilter.Favorites -> recipes.filter { it.isFavorite }
            RecipeFilter.WantToMake -> recipes.filter { it.wantToMake }
            else -> recipes
        }
    }
    val searched = remember(flagged, query, searchFields, courses, categoryNamesByRecipe, tagNamesByRecipe) {
        if (query.isBlank()) {
            flagged
        } else {
            val courseNames = courses.associate { it.id to it.name.orEmpty() }
            flagged.filter { r ->
                RecipeSearch.matches(
                    RecipeSearchFields(
                        name = r.name,
                        introduction = r.introduction,
                        ingredients = r.ingredients.map { it.text },
                        notes = r.notes.flatMap { listOf(it.title, it.content) },
                        variations = r.variations.flatMap { listOf(it.variationName, it.text) },
                        courseName = r.courseId?.let { courseNames[it] },
                        categoryNames = categoryNamesByRecipe[r.id].orEmpty(),
                        tagNames = tagNamesByRecipe[r.id].orEmpty(),
                    ),
                    query,
                    searchFields,
                )
            }
        }
    }
    val sorted = remember(searched, sort, ascending) { sortRecipes(searched, sort, ascending) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search ${filter.title}") },
                            singleLine = true,
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                }
                            } else null,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                // Escape closes search, the way it does in every desktop search field.
                                // Handled on the field rather than on the window so it can never fire
                                // while the user is typing somewhere else.
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                        searchActive = false
                                        query = ""
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    } else {
                        Text(filter.title)
                    }
                },
                navigationIcon = navigationIcon,
                actions = {
                    if (searchActive) {
                        Box {
                            IconButton(onClick = { searchOptionsMenu = true }) {
                                Icon(Icons.Filled.FilterList, contentDescription = "Search options")
                            }
                            DropdownMenu(
                                expanded = searchOptionsMenu,
                                onDismissRequest = { searchOptionsMenu = false },
                            ) {
                                Text(
                                    "Search in",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                RecipeSearchField.entries.forEach { field ->
                                    val on = field in searchFields
                                    DropdownMenuItem(
                                        text = { Text(field.label) },
                                        leadingIcon = if (on) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            // Never let the last option be switched off: an empty set
                                            // would search nothing and read as "no recipes match".
                                            val next = if (on) searchFields - field else searchFields + field
                                            searchFields = next.ifEmpty { RecipeSearchField.DEFAULTS }
                                            module.settings.searchFields = searchFields
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { searchActive = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                        return@TopAppBar
                    }
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            RecipeSort.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    leadingIcon = if (opt == sort) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    onClick = { sort = opt; module.settings.recipeSort = opt.name; sortMenu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (ascending) "Ascending ↑" else "Descending ↓") },
                                onClick = {
                                    ascending = !ascending
                                    module.settings.recipeSortAscending = ascending
                                    sortMenu = false
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import from Web…") },
                                onClick = { overflowMenu = false; shell.onImportFromWeb() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // One FAB, one primary action. "Import from Web…" is a secondary path and lives in the
            // app bar's overflow instead — a FAB that opens a menu isn't a Material pattern.
            FloatingActionButton(onClick = { shell.onScreen(Screen.Edit(null)) }) {
                Icon(Icons.Filled.Add, contentDescription = "New recipe")
            }
        },
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    query.isNotBlank() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No matches",
                        body = "Nothing in ${filter.title} matches \"${query.trim()}\". Try a different " +
                            "term, or search more fields from the filter button.",
                    )
                    filter is RecipeFilter.All -> EmptyState(
                        icon = Icons.Outlined.Restaurant,
                        title = "No recipes yet",
                        body = "Add one by hand, import a recipe from the web, or sync with Salty Server.",
                        actionLabel = "New recipe",
                        onAction = { shell.onScreen(Screen.Edit(null)) },
                    )
                    else -> EmptyState(
                        icon = Icons.Outlined.Restaurant,
                        title = "Nothing in ${filter.title}",
                        body = "Recipes filed under ${filter.title} will appear here.",
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(sorted, key = { it.id }) { recipe ->
                    // Prefer the cached thumbnail blob; fall back to the full image for rows synced
                    // before thumbnail caching (a re-sync backfills the blob).
                    val thumb = remember(recipe.imageThumbnailData, recipe.imageFilename) {
                        (recipe.imageThumbnailData
                            ?: recipe.imageFilename?.let { module.imageFiles.load(it) })
                            ?.let { decodeImageBitmap(it) }
                    }
                    var rowMenu by remember(recipe.id) { mutableStateOf(false) }
                    // When sorting by "Last Made", surface the date in the row itself — otherwise the
                    // ordering has no visible explanation.
                    val lastMade = remember(recipe.lastPrepared) {
                        PreparedDates.formatForDisplay(LocalStore.dbToWireDate(recipe.lastPrepared))
                    }
                    val subtitle = when {
                        sort == RecipeSort.LAST_MADE -> lastMade?.let { "Made $it" } ?: "Never made"
                        else -> recipe.source?.takeIf { it.isNotBlank() }
                    }
                    Box {
                        ListItem(
                            leadingContent = { RecipeThumbnail(thumb) },
                            headlineContent = { Text(if (recipe.isFavorite == true) "★ ${recipe.name}" else recipe.name) },
                            supportingContent = subtitle?.let { { Text(it) } },
                            // In a split view the row for the recipe showing on the right is marked, so
                            // the list always says which one you're reading.
                            colors = if (recipe.id == selectedId) {
                                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            } else {
                                ListItemDefaults.colors()
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { shell.onScreen(Screen.Detail(recipe.id)) },
                                onLongClick = { rowMenu = true },
                            ),
                        )
                        LastMadeMenu(
                            expanded = rowMenu,
                            currentLastPrepared = recipe.lastPrepared,
                            onDismiss = { rowMenu = false },
                            onSet = { wire ->
                                module.localStore.setRecipePrepared(recipe.id, wire, nowTimestamp())
                                module.onLocalChange()
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Long-press menu for a recipe's "last made on" date, plus the date picker "Set Date…" opens.
 *
 * [onSet] receives the wire timestamp to store (null clears the date); the caller pairs it with a fresh
 * `lastModifiedPreparedDate` and — deliberately — leaves `lastModifiedDate` alone, so marking a recipe
 * made never reorders the "Date Modified" sort. "Clear" is offered because a single date field
 * overwrites irreversibly, so a mis-tap needs a way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LastMadeMenu(
    expanded: Boolean,
    currentLastPrepared: String?,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Made Today") },
            onClick = { onSet(nowTimestamp()); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Set Date…") },
            onClick = { onDismiss(); showPicker = true },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Clear") },
            onClick = { onSet(null); onDismiss() },
        )
    }

    if (showPicker) {
        // Seeded from the stored date so re-picking starts where the user left off. Future days are
        // unselectable: a recipe can't have been made in the future.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = PreparedDates.wireToPickerMillis(
                LocalStore.dbToWireDate(currentLastPrepared)
            ),
            selectableDates = PastOrPresentDates,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { onSet(PreparedDates.pickerMillisToWire(it)) }
                        showPicker = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/** Restricts the "last made" picker to days that have already happened. */
@OptIn(ExperimentalMaterial3Api::class)
private object PastOrPresentDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= PreparedDates.nowEpochMillis()

    override fun isSelectableYear(year: Int): Boolean =
        year <= PreparedDates.currentLocalYear()
}

/**
 * The navigation list itself, with no sheet around it — the caller supplies a [ModalDrawerSheet] on
 * phone widths or a [PermanentDrawerSheet] on a desktop-sized window, and the same contents serve both.
 *
 * [onNavigated] runs after any destination is picked; a modal drawer closes itself there, a permanent
 * one does nothing.
 */
@Composable
private fun SaltyDrawerContents(shell: AppShell, onNavigated: () -> Unit = {}) {
    val module = shell.module
    val courses by module.repository.courses().collectAsState(initial = emptyList())
    val categories by module.repository.categories().collectAsState(initial = emptyList())
    val tags by module.repository.tags().collectAsState(initial = emptyList())
    // Collapsed state is persisted (see SettingsState) — a library with dozens of tags gets collapsed once.
    var collapsed by remember { mutableStateOf(module.settings.collapsedDrawerSections) }
    var editLibraryMenu by remember { mutableStateOf(false) }
    // A filter only counts as "the current destination" while the recipes area is showing; on Settings or
    // a shopping list, nothing in the recipe half of the sidebar should look active.
    val selected = shell.filter.takeIf { shell.onRecipes }

    fun toggle(kind: ClassifierKind) {
        collapsed = if (kind.name in collapsed) collapsed - kind.name else collapsed + kind.name
        module.settings.collapsedDrawerSections = collapsed
    }

    fun go(filter: RecipeFilter) {
        shell.openFilter(filter)
        onNavigated()
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "Salty",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )

        // Fixed destinations first, each with an icon so they're scannable apart from the (unadorned)
        // classifier entries below.
        DrawerDestination(
            icon = Icons.Outlined.Restaurant,
            label = "All Recipes",
            selected = selected is RecipeFilter.All,
            onClick = { go(RecipeFilter.All) },
        )
        DrawerDestination(
            icon = Icons.Filled.Star,
            label = "Favorites",
            selected = selected is RecipeFilter.Favorites,
            onClick = { go(RecipeFilter.Favorites) },
        )
        DrawerDestination(
            icon = Icons.Filled.BookmarkAdded,
            label = "Want to Make",
            selected = selected is RecipeFilter.WantToMake,
            onClick = { go(RecipeFilter.WantToMake) },
        )
        DrawerDestination(
            icon = Icons.AutoMirrored.Filled.ListAlt,
            label = "Shopping Lists",
            selected = shell.screen is Screen.ShoppingLists || shell.screen is Screen.ShoppingListDetail,
            onClick = { shell.onScreen(Screen.ShoppingLists); onNavigated() },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Categories/Courses/Tags, each collapsible. Sections render even when empty so the header
        // still says the section exists.
        for (kind in listOf(ClassifierKind.Categories, ClassifierKind.Courses, ClassifierKind.Tags)) {
            val entries: List<Pair<String, String>> = when (kind) {
                ClassifierKind.Categories -> categories.map { it.id to (it.name ?: "(unnamed)") }
                ClassifierKind.Courses -> courses.map { it.id to (it.name ?: "(unnamed)") }
                ClassifierKind.Tags -> tags.map { it.id to (it.name ?: "(unnamed)") }
            }
            val isCollapsed = kind.name in collapsed
            DrawerSectionHeader(
                title = kind.title,
                collapsed = isCollapsed,
                onToggle = { toggle(kind) },
            )
            if (!isCollapsed) {
                entries.forEach { (id, name) ->
                    val filter = when (kind) {
                        ClassifierKind.Categories -> RecipeFilter.Category(id, name)
                        ClassifierKind.Courses -> RecipeFilter.Course(id, name)
                        ClassifierKind.Tags -> RecipeFilter.Tag(id, name)
                    }
                    val isSelected = when (kind) {
                        ClassifierKind.Categories -> (selected as? RecipeFilter.Category)?.id == id
                        ClassifierKind.Courses -> (selected as? RecipeFilter.Course)?.id == id
                        ClassifierKind.Tags -> (selected as? RecipeFilter.Tag)?.id == id
                    }
                    NavigationDrawerItem(
                        label = { Text(name) },
                        selected = isSelected,
                        onClick = { go(filter) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // Editing the classifiers is rare and app-level, so it sits down here with Settings as one row
        // that opens a menu — not an "Edit …" row under each of the three sections above.
        Box {
            DrawerDestination(
                icon = Icons.Filled.Edit,
                label = "Edit Classifiers…",
                selected = shell.screen is Screen.ManageClassifier,
                onClick = { editLibraryMenu = true },
            )
            DropdownMenu(expanded = editLibraryMenu, onDismissRequest = { editLibraryMenu = false }) {
                for (kind in listOf(ClassifierKind.Categories, ClassifierKind.Courses, ClassifierKind.Tags)) {
                    DropdownMenuItem(
                        text = { Text(kind.title) },
                        leadingIcon = if (shell.screen == Screen.ManageClassifier(kind)) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            editLibraryMenu = false
                            shell.onScreen(Screen.ManageClassifier(kind))
                            onNavigated()
                        },
                    )
                }
            }
        }
        // Settings is app-level and infrequent, so it lives here rather than taking a slot in the
        // recipe list's app bar (which is for actions on the list itself).
        DrawerDestination(
            icon = Icons.Filled.Settings,
            label = "Settings",
            selected = shell.screen is Screen.Settings,
            onClick = { shell.onScreen(Screen.Settings); onNavigated() },
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Standard empty state: icon, short headline, a line of guidance, and — where there's an obvious next
 * step — a button for it. A bare centered sentence leaves the user to work out what to do next.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.padding(32.dp).widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A top-level drawer destination: icon + label, in the standard drawer inset. */
@Composable
private fun DrawerDestination(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Collapsible section header: title, item count, and a chevron. Whole row is the toggle. */
@Composable
private fun DrawerSectionHeader(title: String, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) "Expand $title" else "Collapse $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small rounded thumbnail for a recipe list row, with a placeholder when there's no image. */
@Composable
private fun RecipeThumbnail(image: ImageBitmap?) {
    val shape = RoundedCornerShape(5.dp)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        ImagePlaceholder(Modifier.size(56.dp).clip(shape))
    }
}

/** Drawn (not stored) placeholder for recipes with no image — a Material icon on a muted surface. */
@Composable
private fun ImagePlaceholder(modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Restaurant,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.5f),
        )
    }
}

/** Empty-image affordance for the editor: a dashed, tappable "add a photo" target (not a thumbnail). */
@Composable
private fun AddImageTarget(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    ),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = onSurfaceVariant)
            Text("Add image", color = onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecipeDetailScreen(
    module: AppModule,
    id: String,
    /** The app-bar back button; null in a split view, where the list it would return to never left. */
    onBack: (() -> Unit)?,
    /** Leave this recipe — used after deleting it, where staying would show a blank screen. */
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onFilter: (RecipeFilter) -> Unit,
    onDeleted: (message: String, undo: () -> Unit) -> Unit,
    wide: Boolean = false,
) {
    // This screen reads the recipe once rather than collecting a flow; bumping [reload] re-reads it after
    // an edit made from here (setting "Last Made"), which would otherwise not show until it was reopened.
    var reload by remember(id) { mutableStateOf(0) }
    // recipeForUpload gives the full ServerRecipe incl. category/tag ids (the db row omits junctions).
    val recipe = remember(id, reload) { module.localStore.recipeForUpload(id) }
    // The DB row carries the image state (thumbnail blob + image timestamp) that the wire shape doesn't,
    // so keep it for restoring the recipe if the delete is undone.
    val row = remember(id, reload) { module.repository.recipe(id) }
    val image = remember(recipe?.imageFilename) {
        recipe?.imageFilename?.let { fn -> module.imageFiles.load(fn)?.let { decodeImageBitmap(it) } }
    }
    val courseName = remember(id) {
        recipe?.courseId?.let { cid -> module.localStore.courses().firstOrNull { it.id == cid }?.name?.takeIf { it.isNotBlank() } }
    }
    val categoryChips = remember(id) {
        val m = module.localStore.categories().associate { it.id to it.name }
        recipe?.categoryIds.orEmpty().mapNotNull { cid -> m[cid]?.takeIf { it.isNotBlank() }?.let { cid to it } }
    }
    val tagChips = remember(id) {
        val m = module.localStore.tags().associate { it.id to it.name }
        recipe?.tagIds.orEmpty().mapNotNull { tid -> m[tid]?.takeIf { it.isNotBlank() }?.let { tid to it } }
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var lastMadeMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.name ?: "Recipe") },
                navigationIcon = {
                    // No back arrow in a two-pane layout: the list it would return to is still on screen.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                // The list's long-press is the only other route to this, and a long press
                                // is not something anyone discovers — so the recipe carries it too.
                                DropdownMenuItem(
                                    text = { Text("Last Made…") },
                                    onClick = { overflowMenu = false; lastMadeMenu = true },
                                )
                            }
                            LastMadeMenu(
                                expanded = lastMadeMenu,
                                currentLastPrepared = row?.lastPrepared,
                                onDismiss = { lastMadeMenu = false },
                                onSet = { wire ->
                                    module.localStore.setRecipePrepared(id, wire, nowTimestamp())
                                    module.onLocalChange()
                                    reload++
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (recipe == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Not found") }
            return@Scaffold
        }
        // A recipe read across a 1600px window is unreadable; cap the measure and centre it. On a phone
        // the cap is never reached, so nothing changes there.
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = READING_WIDTH).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = recipe.name,
                    // Wide layouts get a hero across the pane; a phone keeps the compact square, where a
                    // 240dp-tall image would push everything else below the fold.
                    modifier = if (wide) {
                        Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp))
                    } else {
                        Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
                    },
                    contentScale = ContentScale.Crop,
                )
            }

            // Metadata card
            val metaItems = buildList {
                courseName?.let { add("Course" to it) }
                difficultyName(recipe.difficulty)?.let { add("Difficulty" to it) }
                ratingStars(recipe.rating)?.let { add("Rating" to it) }
                recipe.servings?.let { add("Servings" to it.toString()) }
                recipe.yield?.takeIf { it.isNotBlank() }?.let { add("Yield" to it) }
                PreparedDates.formatForDisplay(LocalStore.dbToWireDate(recipe.lastPrepared))
                    ?.let { add("Last Made" to it) }
            }
            val flags = buildList {
                if (recipe.isFavorite == true) add(Icons.Filled.Star to "Favorite")
                if (recipe.wantToMake == true) add(Icons.Filled.BookmarkAdded to "Want to Make")
            }
            if (metaItems.isNotEmpty() || flags.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        metaItems.forEach { (k, v) -> DetailMeta(k, v) }
                        if (flags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                flags.forEach { (icon, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            recipe.introduction?.takeIf { it.isNotBlank() }?.let { DetailSection("Introduction") { Text(it) } }

            recipe.preparationTimes?.takeIf { it.isNotEmpty() }?.let { list ->
                DetailSection("Preparation Times") {
                    list.forEach { DetailMeta(it.type, it.timeString) }
                }
            }
            recipe.ingredients?.takeIf { it.isNotEmpty() }?.let { list ->
                DetailSection("Ingredients") {
                    list.forEach {
                        if (it.isHeading) Text(it.text, fontWeight = FontWeight.Medium)
                        else Text("• ${it.text}")
                    }
                }
            }
            recipe.directions?.takeIf { it.isNotEmpty() }?.let { list ->
                DetailSection("Directions") {
                    var step = 0
                    list.forEach { d ->
                        // Headings (e.g. "Prepare filling") are medium-weight (below the section title)
                        // and not numbered/counted.
                        if (d.isHeading == true) Text(d.text, fontWeight = FontWeight.Medium)
                        else { step++; Text("$step. ${d.text}") }
                    }
                }
            }
            recipe.notes?.takeIf { it.isNotEmpty() }?.let { list ->
                DetailSection("Notes") {
                    list.forEach { n ->
                        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall)
                        Text(n.content)
                    }
                }
            }
            recipe.variations?.takeIf { it.isNotEmpty() }?.let { list ->
                DetailSection("Variations") {
                    list.forEach { v ->
                        if (v.variationName.isNotBlank()) Text(v.variationName, style = MaterialTheme.typography.titleSmall)
                        Text(v.text)
                    }
                }
            }
            recipe.nutrition?.let { n ->
                val rows = nutritionRows(n)
                if (rows.isNotEmpty()) DetailSection("Nutrition") { rows.forEach { (k, v) -> DetailMeta(k, v) } }
            }
            // Chips navigate: tapping one shows the rest of the library filed under it. (They used to be
            // AssistChips with an empty onClick — tappable-looking and inert.)
            if (categoryChips.isNotEmpty()) DetailSection("Categories") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categoryChips.forEach { (cid, name) ->
                        AssistChip(onClick = { onFilter(RecipeFilter.Category(cid, name)) }, label = { Text(name) })
                    }
                }
            }
            if (tagChips.isNotEmpty()) DetailSection("Tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tagChips.forEach { (tid, name) ->
                        AssistChip(onClick = { onFilter(RecipeFilter.Tag(tid, name)) }, label = { Text(name) })
                    }
                }
            }
            if (!recipe.source.isNullOrBlank() || !recipe.sourceDetails.isNullOrBlank()) {
                DetailSection("Source") {
                    recipe.source?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    recipe.sourceDetails?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            }
        }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete recipe?") },
            text = { Text("\"${recipe?.name.orEmpty()}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = recipe
                    module.localStore.deleteRecipe(id)
                    module.onLocalChange()
                    showDeleteConfirm = false
                    onClose()
                    // Deleting propagates to the server and every other device, so offer a way back. Undo
                    // must also drop the tombstone, or the next sync would delete the restored recipe again.
                    if (deleted != null) {
                        onDeleted("Deleted \"${deleted.name}\"") {
                            module.localStore.upsertRecipe(deleted)
                            module.localStore.setRecipeImage(
                                id, row?.imageFilename, row?.imageThumbnailData, row?.lastModifiedImageDate,
                            )
                            module.localStore.clearRecipeTombstones(listOf(id))
                            module.onLocalChange()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
    content()
}

@Composable
private fun DetailMeta(label: String, value: String) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
        append(value)
    })
}

private fun difficultyName(v: Int?): String? = when (v) {
    1 -> "Easy"
    2 -> "Somewhat Easy"
    3 -> "Medium"
    4 -> "Slightly Difficult"
    5 -> "Difficult"
    else -> null
}

private fun ratingStars(v: Int?): String? = if (v != null && v in 1..5) "★".repeat(v) + "☆".repeat(5 - v) else null

private fun nutritionRows(n: com.enuvro.saltykmp.db.model.NutritionInformation): List<Pair<String, String>> = buildList {
    fun num(d: Double?) = d?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
    n.servingSize?.takeIf { it.isNotBlank() }?.let { add("Serving Size" to it) }
    num(n.calories)?.let { add("Calories" to it) }
    num(n.protein)?.let { add("Protein" to "${it}g") }
    num(n.carbohydrates)?.let { add("Carbs" to "${it}g") }
    num(n.fat)?.let { add("Fat" to "${it}g") }
    num(n.fiber)?.let { add("Fiber" to "${it}g") }
    num(n.sugar)?.let { add("Sugar" to "${it}g") }
    num(n.sodium)?.let { add("Sodium" to "${it}mg") }
    num(n.cholesterol)?.let { add("Cholesterol" to "${it}mg") }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RecipeEditScreen(
    module: AppModule,
    id: String?,
    imported: ImportedRecipe? = null,
    /**
     * Leaving the editor. The argument is the id of the recipe that was just saved, or null if the edit
     * was cancelled — so saving a brand-new recipe can land on it instead of dumping the user back at the
     * top of the library, which is what the Swift app does.
     */
    onDone: (savedId: String?) -> Unit,
) {
    // A web import seeds the editor exactly like an existing recipe would, so every field, the save path,
    // and the id all work unchanged — the difference is that nothing is in the DB until the user saves.
    val existing = remember(id, imported) { id?.let { module.localStore.recipeForUpload(it) } ?: imported?.recipe }
    // The DB row carries the cached thumbnail blob, which we must preserve across edits.
    val existingRow = remember(id) { id?.let { module.repository.recipe(it) } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // A blank name disables Save. Only flag the field red once the user has been in it, so a fresh
    // "New Recipe" form doesn't open already shouting an error.
    var nameTouched by remember(id) { mutableStateOf(false) }
    var intro by remember { mutableStateOf(existing?.introduction ?: "") }
    var favorite by remember { mutableStateOf(existing?.isFavorite ?: false) }
    var wantToMake by remember { mutableStateOf(existing?.wantToMake ?: false) }
    var courseId by remember(id) { mutableStateOf(existing?.courseId) }
    var difficulty by remember(id) { mutableStateOf(existing?.difficulty) }
    var rating by remember(id) { mutableStateOf(existing?.rating) }
    var servings by remember { mutableStateOf(existing?.servings?.toString() ?: "") }
    var yieldText by remember { mutableStateOf(existing?.yield ?: "") }
    var source by remember { mutableStateOf(existing?.source ?: "") }
    var sourceDetails by remember { mutableStateOf(existing?.sourceDetails ?: "") }
    val selectedCategories = remember(id) {
        mutableStateListOf<String>().also { it.addAll(existing?.categoryIds.orEmpty()) }
    }
    val selectedTags = remember(id) {
        mutableStateListOf<String>().also { it.addAll(existing?.tagIds.orEmpty()) }
    }
    // Image edit state: pickedImage holds freshly chosen bytes; imageRemoved clears an existing image.
    var pickedImage by remember(id) { mutableStateOf<ByteArray?>(imported?.imageBytes) }
    var imageRemoved by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val courses by module.repository.courses().collectAsState(initial = emptyList())
    val categories by module.repository.categories().collectAsState(initial = emptyList())
    val tags by module.repository.tags().collectAsState(initial = emptyList())

    // Editable lists carry the section flags so the editor can add/toggle headings (and "main" ingredients),
    // matching the SwiftUI app. Item ids are preserved across a save.
    val ingredients = remember(id) {
        mutableStateListOf<IngredientRow>().also { l ->
            existing?.ingredients?.forEach { l.add(IngredientRow(it.id, it.text, it.isHeading, it.isMain)) }
        }
    }
    val directions = remember(id) {
        mutableStateListOf<DirectionRow>().also { l ->
            existing?.directions?.forEach { l.add(DirectionRow(it.id, it.text, it.isHeading == true)) }
        }
    }
    // Two-field structured lists held as (id, a, b) triples so item ids survive edits.
    val notes = remember(id) {
        mutableStateListOf<Triple<String, String, String>>().also { l -> existing?.notes?.forEach { l.add(Triple(it.id, it.title, it.content)) } }
    }
    val variations = remember(id) {
        mutableStateListOf<Triple<String, String, String>>().also { l -> existing?.variations?.forEach { l.add(Triple(it.id, it.variationName, it.text)) } }
    }
    val prepTimes = remember(id) {
        mutableStateListOf<Triple<String, String, String>>().also { l -> existing?.preparationTimes?.forEach { l.add(Triple(it.id, it.type, it.timeString)) } }
    }
    val nutrition = remember(id) {
        mutableStateMapOf<String, String>().also { m -> existing?.nutrition?.let { fillNutritionInputs(it, m) } }
    }

    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.Image) { file: PlatformFile? ->
        if (file != null) scope.launch {
            pickedImage = file.readBytes()
            imageRemoved = false
        }
    }
    val takePhoto = rememberCameraCapture { bytes ->
        if (bytes != null) {
            pickedImage = bytes
            imageRemoved = false
        }
    }

    val preview: ImageBitmap? = remember(pickedImage, imageRemoved, existing?.imageFilename) {
        when {
            imageRemoved -> null
            pickedImage != null -> decodeImageBitmap(pickedImage!!)
            else -> existing?.imageFilename?.let { fn -> module.imageFiles.load(fn)?.let { decodeImageBitmap(it) } }
        }
    }

    val save: () -> Unit = {
        val recipeId = existing?.id ?: newId()
        val base = existing ?: ServerRecipe(id = recipeId, name = name)
        val finalFilename = when {
            imageRemoved -> null
            pickedImage != null -> existing?.imageFilename ?: "$recipeId.jpg"
            else -> existing?.imageFilename
        }
        module.localStore.upsertRecipe(
            base.copy(
                name = name,
                introduction = intro.ifBlank { null },
                isFavorite = favorite,
                wantToMake = wantToMake,
                courseId = courseId,
                difficulty = difficulty,
                rating = rating,
                servings = servings.toIntOrNull(),
                yield = yieldText.ifBlank { null },
                source = source.ifBlank { null },
                sourceDetails = sourceDetails.ifBlank { null },
                imageFilename = finalFilename,
                categoryIds = selectedCategories.toList(),
                tagIds = selectedTags.toList(),
                ingredients = ingredients.filter { it.text.isNotBlank() }
                    .map { Ingredient(id = it.id, isHeading = it.isHeading, isMain = it.isMain && !it.isHeading, text = it.text) },
                directions = directions.filter { it.text.isNotBlank() }
                    .map { Direction(id = it.id, isHeading = it.isHeading, text = it.text) },
                preparationTimes = prepTimes.filter { it.second.isNotBlank() || it.third.isNotBlank() }
                    .map { PreparationTime(id = it.first, type = it.second, timeString = it.third) },
                notes = notes.filter { it.second.isNotBlank() || it.third.isNotBlank() }
                    .map { Note(id = it.first, title = it.second, content = it.third) },
                variations = variations.filter { it.second.isNotBlank() || it.third.isNotBlank() }
                    .map { Variation(id = it.first, variationName = it.second, text = it.third) },
                nutrition = buildNutrition(existing?.nutrition?.id, nutrition),
                lastModifiedDate = nowTimestamp(),
            ),
        )
        // upsertRecipe resets the thumbnail blob, so always re-establish the image state here:
        // new pick → save file + thumbnail; removed → clear; unchanged → restore cached thumbnail.
        val bytes = pickedImage
        when {
            // Image set/replaced or removed → stamp a fresh image timestamp (now); unchanged → preserve the
            // row's existing image date so a text-only save never looks like an image change to sync.
            bytes != null && finalFilename != null -> {
                module.imageFiles.save(finalFilename, bytes)
                module.localStore.setRecipeImage(recipeId, finalFilename, makeThumbnail(bytes, 300), nowTimestamp())
            }
            imageRemoved -> module.localStore.setRecipeImage(recipeId, null, null, nowTimestamp())
            else -> module.localStore.setRecipeImage(recipeId, existingRow?.imageFilename, existingRow?.imageThumbnailData, existingRow?.lastModifiedImageDate)
        }
        module.onLocalChange()
        onDone(recipeId)
    }

    // Cancelling throws away everything typed, so compare the form against the state it opened in and
    // only interrupt with a confirmation when there is actually something to lose. Picked image bytes
    // are compared as "was the image touched at all" rather than by content.
    fun formSnapshot(): List<Any?> = listOf(
        name, intro, favorite, wantToMake, courseId, difficulty, rating, servings, yieldText, source,
        sourceDetails, selectedCategories.toList(), selectedTags.toList(), ingredients.toList(),
        directions.toList(), notes.toList(), variations.toList(), prepTimes.toList(), nutrition.toMap(),
        pickedImage != null, imageRemoved,
    )
    val initialForm = remember(id, imported) { formSnapshot() }
    var confirmDiscard by remember(id) { mutableStateOf(false) }
    val cancel: () -> Unit = { if (formSnapshot() == initialForm) onDone(id) else confirmDiscard = true }

    // Registered deeper than the app-level handler, so it wins: system/gesture back can't silently
    // discard an edit in progress.
    BackHandler(enabled = true) { cancel() }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text(if (id == null) "This recipe hasn't been saved yet." else "Your edits to this recipe haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone(id) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep Editing") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New Recipe" else "Edit Recipe") },
                navigationIcon = {
                    IconButton(onClick = cancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    IconButton(onClick = save, enabled = name.isNotBlank()) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        // Form fields stretched across a desktop window are hard to scan; cap and centre the column.
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = FORM_WIDTH).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                name,
                { name = it; nameTouched = true },
                label = { Text("Name") },
                singleLine = true,
                isError = nameTouched && name.isBlank(),
                supportingText = if (nameTouched && name.isBlank()) {
                    { Text("A name is required to save") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(intro, { intro = it }, label = { Text("Introduction") }, modifier = Modifier.fillMaxWidth())

            EditSectionHeader("Image")
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = name,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { galleryLauncher.launch() }) { Text("Change") }
                    if (takePhoto != null) OutlinedButton(onClick = takePhoto) { Text("Take Photo") }
                    TextButton(onClick = { pickedImage = null; imageRemoved = true }) { Text("Remove") }
                }
            } else {
                // Empty state: a dashed "drop target" that reads as an add-image slot, not a thumbnail.
                AddImageTarget(onClick = { galleryLauncher.launch() })
                if (takePhoto != null) {
                    TextButton(onClick = takePhoto) { Text("Take Photo") }
                }
            }

            EditSectionHeader("Details")
            // FlowRow so the two toggles wrap onto a second line on a narrow phone instead of clipping.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Favorite")
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = favorite, onCheckedChange = { favorite = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Want to Make")
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = wantToMake, onCheckedChange = { wantToMake = it })
                }
            }

            PickerField(
                label = "Course",
                options = listOf("None" to null) + courses.map { (it.name ?: "(unnamed)") to it.id },
                selected = courseId,
                onSelect = { courseId = it },
            )
            PickerField(
                label = "Difficulty",
                options = listOf<Pair<String, Int?>>(
                    "Not set" to null, "Easy" to 1, "Somewhat Easy" to 2, "Medium" to 3,
                    "Slightly Difficult" to 4, "Difficult" to 5,
                ),
                selected = difficulty,
                onSelect = { difficulty = it },
            )
            RatingField(rating) { rating = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    servings, { servings = it.filter(Char::isDigit) },
                    label = { Text("Servings") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    yieldText, { yieldText = it },
                    label = { Text("Yield") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }

            if (categories.isNotEmpty()) {
                MultiSelectField("Categories", categories.map { (it.name ?: "(unnamed)") to it.id }, selectedCategories)
            }
            if (tags.isNotEmpty()) {
                MultiSelectField("Tags", tags.map { (it.name ?: "(unnamed)") to it.id }, selectedTags)
            }

            OutlinedTextField(source, { source = it }, label = { Text("Source") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sourceDetails, { sourceDetails = it }, label = { Text("Source details") }, modifier = Modifier.fillMaxWidth())

            IngredientEditList(ingredients)
            DirectionEditList(directions)
            EditablePairList("Preparation Times", prepTimes, "Type (e.g. Prep)", "Time (e.g. 20 min)", "+ Add time")
            EditablePairList("Notes", notes, "Title", "Note", "+ Add note")
            EditablePairList("Variations", variations, "Name", "Details", "+ Add variation")
            NutritionSection(nutrition)
        }
        }
    }
}

/**
 * Rating as five tappable stars rather than a dropdown listing "★★★". Tapping the star that's already
 * the rating clears it, which is the only way back to "not set" without a menu entry for it.
 */
@Composable
private fun RatingField(rating: Int?, onRating: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Rating", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { star ->
                val filled = rating != null && star <= rating
                IconButton(onClick = { onRating(if (rating == star) null else star) }) {
                    Icon(
                        if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "$star star${if (star == 1) "" else "s"}",
                        tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (rating != null) {
                TextButton(onClick = { onRating(null) }) { Text("Clear") }
            }
        }
    }
}

// Editable ingredient/direction rows. A "heading" row is a section title (not bulleted/numbered when
// displayed). Headings are created via the "Add heading" button; there's no per-row toggle (keeps the UI
// clean). The flags are still carried on each row so existing headings — and the ingredient "main" flag
// set in the SwiftUI app, which has no CMP UI yet — round-trip through a save untouched.
private data class IngredientRow(val id: String, val text: String = "", val isHeading: Boolean = false, val isMain: Boolean = false)
private data class DirectionRow(val id: String, val text: String = "", val isHeading: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientEditList(items: SnapshotStateList<IngredientRow>) {
    EditSectionHeader("Ingredients")
    items.forEachIndexed { i, item ->
        SectionRow(
            text = item.text,
            isHeading = item.isHeading,
            placeholder = if (item.isHeading) "Section heading" else "Ingredient",
            canMoveUp = i > 0,
            canMoveDown = i < items.lastIndex,
            onMoveUp = { items.swapItems(i, i - 1) },
            onMoveDown = { items.swapItems(i, i + 1) },
            onTextChange = { items[i] = item.copy(text = it) },
            onRemove = { items.removeAt(i) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { items.add(IngredientRow(newId())) }) { Text("+ Add ingredient") }
        TextButton(onClick = { items.add(IngredientRow(newId(), isHeading = true)) }) { Text("+ Add heading") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionEditList(items: SnapshotStateList<DirectionRow>) {
    EditSectionHeader("Directions")
    // Step numbers count only the non-heading rows, so a "For the sauce" heading doesn't eat a number.
    val numbers = buildList {
        var n = 0
        items.forEach { add(if (it.isHeading) null else { n += 1; "$n." }) }
    }
    items.forEachIndexed { i, item ->
        SectionRow(
            text = item.text,
            isHeading = item.isHeading,
            placeholder = if (item.isHeading) "Section heading" else "Step",
            canMoveUp = i > 0,
            canMoveDown = i < items.lastIndex,
            onMoveUp = { items.swapItems(i, i - 1) },
            onMoveDown = { items.swapItems(i, i + 1) },
            onTextChange = { items[i] = item.copy(text = it) },
            onRemove = { items.removeAt(i) },
            number = numbers[i],
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { items.add(DirectionRow(newId())) }) { Text("+ Add step") }
        TextButton(onClick = { items.add(DirectionRow(newId(), isHeading = true)) }) { Text("+ Add heading") }
    }
}

/** Swap the items at [i] and [j]; ignores indices that fall off either end of the list. */
private fun <T> SnapshotStateList<T>.swapItems(i: Int, j: Int) {
    if (i in indices && j in indices) {
        val held = this[i]
        this[i] = this[j]
        this[j] = held
    }
}

/** A single ingredient/direction text field + reorder/remove controls; heading rows render bold so they
 * read as section titles even though there's no per-row toggle. [number] gutters a step number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionRow(
    text: String,
    isHeading: Boolean,
    placeholder: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit,
    number: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (number != null) {
            Text(
                number,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(placeholder) },
            textStyle = if (isHeading) LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold) else LocalTextStyle.current,
            modifier = Modifier.weight(1f),
        )
        // A mistyped order used to mean retyping every row below it; these move the row instead. Half-height
        // so the pair takes no more width than one icon button.
        Column {
            RowMoveButton(Icons.Filled.KeyboardArrowUp, "Move up", canMoveUp, onMoveUp)
            RowMoveButton(Icons.Filled.KeyboardArrowDown, "Move down", canMoveDown, onMoveDown)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
    }
}

@Composable
private fun RowMoveButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/**
 * A labeled single-choice dropdown; [options] are (display, value) pairs and value may be null ("none").
 *
 * Built on [ExposedDropdownMenuBox] with a read-only text field rather than a button, so it matches the
 * height, floating label, and outline of the real text fields it sits between in the editor — and carries
 * the dropdown's accessibility semantics, which a plain button doesn't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PickerField(label: String, options: List<Pair<String, T?>>, selected: T?, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first ?: "—"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

/**
 * Multi-select as a dropdown of checkable rows; mutates [selected] in place.
 *
 * A flat list of every category/tag as chips reads as "these are all already applied" — the selected
 * state is only legible once you compare two chips side by side. Collapsed to a [PickerField]-shaped
 * field, the closed state shows exactly what is applied (wrapping onto more lines when there are
 * several) and the menu is unambiguously a place to choose from. The menu stays open while checking
 * boxes, since picking several at once is the normal case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectField(label: String, options: List<Pair<String, String>>, selected: SnapshotStateList<String>) {
    var expanded by remember { mutableStateOf(false) }
    // Listed in option order rather than the order they were checked, so the summary doesn't reshuffle
    // itself while the menu is open.
    val chosen = options.filter { selected.contains(it.second) }.map { it.first }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            // "None" rather than an empty field, so this reads identically to the Course picker's
            // unset state (M3 only shows a placeholder while focused, which would leave it blank).
            value = chosen.joinToString(", ").ifEmpty { "None" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                val isSel = selected.contains(value)
                DropdownMenuItem(
                    text = { Text(text) },
                    // Decorative: the whole menu row is the toggle, so the checkbox takes no clicks of its own.
                    leadingIcon = { Checkbox(checked = isSel, onCheckedChange = null) },
                    onClick = { if (isSel) selected.remove(value) else selected.add(value) },
                )
            }
        }
    }
}

/** A rule and a bold label between blocks of the (long, single-column) recipe form. */
@Composable
private fun EditSectionHeader(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/** Editable list of two-field items (id, a, b) — e.g. notes (title/body), variations, prep times. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditablePairList(
    title: String,
    items: SnapshotStateList<Triple<String, String, String>>,
    labelA: String,
    labelB: String,
    addLabel: String,
) {
    EditSectionHeader(title)
    items.forEachIndexed { i, item ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.second,
                    onValueChange = { items[i] = item.copy(second = it) },
                    label = { Text(labelA) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
            OutlinedTextField(
                value = item.third,
                onValueChange = { items[i] = item.copy(third = it) },
                label = { Text(labelB) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    TextButton(onClick = { items.add(Triple(newId(), "", "")) }) { Text(addLabel) }
}

/** Optional nutrition fields (key matches NutritionInformation property; servingSize is free text). */
private val NUTRITION_FIELDS = listOf(
    "servingSize" to "Serving size",
    "calories" to "Calories",
    "protein" to "Protein (g)",
    "carbohydrates" to "Carbs (g)",
    "fat" to "Fat (g)",
    "saturatedFat" to "Saturated fat (g)",
    "transFat" to "Trans fat (g)",
    "fiber" to "Fiber (g)",
    "sugar" to "Sugar (g)",
    "addedSugar" to "Added sugar (g)",
    "sodium" to "Sodium (mg)",
    "cholesterol" to "Cholesterol (mg)",
    "vitaminD" to "Vitamin D",
    "calcium" to "Calcium",
    "iron" to "Iron",
    "potassium" to "Potassium",
    "vitaminA" to "Vitamin A",
    "vitaminC" to "Vitamin C",
)

// Nutrition is rarely edited and has many fields, so it's collapsed by default — a tappable header
// (with a filled-value count) expands the inputs only when wanted.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutritionSection(values: SnapshotStateMap<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val filled = values.count { it.value.isNotBlank() }
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Nutrition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (!expanded && filled > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$filled value${if (filled == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse nutrition" else "Expand nutrition",
        )
    }
    if (expanded) {
        NUTRITION_FIELDS.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (key, label) ->
                    OutlinedTextField(
                        value = values[key] ?: "",
                        onValueChange = { values[key] = it },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun numInput(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private fun fillNutritionInputs(n: NutritionInformation, m: MutableMap<String, String>) {
    n.servingSize?.takeIf { it.isNotBlank() }?.let { m["servingSize"] = it }
    fun put(k: String, v: Double?) { v?.let { m[k] = numInput(it) } }
    put("calories", n.calories); put("protein", n.protein); put("carbohydrates", n.carbohydrates)
    put("fat", n.fat); put("saturatedFat", n.saturatedFat); put("transFat", n.transFat)
    put("fiber", n.fiber); put("sugar", n.sugar); put("addedSugar", n.addedSugar)
    put("sodium", n.sodium); put("cholesterol", n.cholesterol); put("vitaminD", n.vitaminD)
    put("calcium", n.calcium); put("iron", n.iron); put("potassium", n.potassium)
    put("vitaminA", n.vitaminA); put("vitaminC", n.vitaminC)
}

private fun buildNutrition(existingId: String?, m: Map<String, String>): NutritionInformation? {
    fun d(k: String) = m[k]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val serving = m["servingSize"]?.trim()?.takeIf { it.isNotEmpty() }
    val n = NutritionInformation(
        id = existingId ?: newId(),
        servingSize = serving,
        calories = d("calories"), protein = d("protein"), carbohydrates = d("carbohydrates"),
        fat = d("fat"), saturatedFat = d("saturatedFat"), transFat = d("transFat"),
        fiber = d("fiber"), sugar = d("sugar"), sodium = d("sodium"), cholesterol = d("cholesterol"),
        addedSugar = d("addedSugar"), vitaminD = d("vitaminD"), calcium = d("calcium"),
        iron = d("iron"), potassium = d("potassium"), vitaminA = d("vitaminA"), vitaminC = d("vitaminC"),
    )
    val allEmpty = serving == null && listOf(
        n.calories, n.protein, n.carbohydrates, n.fat, n.saturatedFat, n.transFat, n.fiber, n.sugar,
        n.sodium, n.cholesterol, n.addedSugar, n.vitaminD, n.calcium, n.iron, n.potassium, n.vitaminA, n.vitaminC,
    ).all { it == null }
    return if (allEmpty) null else n
}

private data class ClassifierItem(val id: String, val name: String)

/**
 * Add / rename / delete one library classifier (courses, categories, or tags) — the KMP equivalent of
 * the Swift app's LibraryCoursesEditView / LibraryCategoryEditView / LibraryTagsEditView. Edits are
 * written locally with a fresh lastModifiedDate; the next sync pushes them to the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassifierEditScreen(module: AppModule, kind: ClassifierKind, onBack: () -> Unit) {
    val items by remember(kind) {
        when (kind) {
            ClassifierKind.Courses -> module.repository.courses().map { l -> l.map { ClassifierItem(it.id, it.name ?: "") } }
            ClassifierKind.Categories -> module.repository.categories().map { l -> l.map { ClassifierItem(it.id, it.name ?: "") } }
            ClassifierKind.Tags -> module.repository.tags().map { l -> l.map { ClassifierItem(it.id, it.name ?: "") } }
        }
    }.collectAsState(initial = emptyList())

    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ClassifierItem?>(null) }

    fun save(id: String, name: String) {
        val ts = nowTimestamp()
        when (kind) {
            ClassifierKind.Courses -> module.localStore.upsertCourse(ServerCourse(id, name, ts))
            ClassifierKind.Categories -> module.localStore.upsertCategory(ServerCategory(id, name, ts))
            ClassifierKind.Tags -> module.localStore.upsertTag(ServerTag(id, name, ts))
        }
        module.onLocalChange()
    }

    fun delete(id: String) {
        when (kind) {
            ClassifierKind.Courses -> module.localStore.deleteCourse(id)
            ClassifierKind.Categories -> module.localStore.deleteCategory(id)
            ClassifierKind.Tags -> module.localStore.deleteTag(id)
        }
        module.onLocalChange()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit ${kind.title}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "New") }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No ${kind.title.lowercase()} added")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(items, key = { it.id }) { item ->
                    ListItem(
                        modifier = Modifier.widthIn(max = FORM_WIDTH).clickable { renaming = item },
                        headlineContent = { Text(item.name.ifBlank { "(unnamed)" }) },
                        trailingContent = {
                            IconButton(onClick = { delete(item.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        },
                    )
                    HorizontalDivider(Modifier.widthIn(max = FORM_WIDTH))
                }
            }
        }
    }

    if (adding) {
        ClassifierNameDialog(
            title = "New ${kind.singular}",
            initial = "",
            confirmLabel = "Add",
            onConfirm = { name -> save(newId(), name); adding = false },
            onDismiss = { adding = false },
        )
    }
    renaming?.let { target ->
        ClassifierNameDialog(
            title = "Rename ${kind.singular}",
            initial = target.name,
            confirmLabel = "Save",
            onConfirm = { name -> save(target.id, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun ClassifierNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(module: AppModule, onBack: () -> Unit) {
    var url by remember { mutableStateOf(module.settings.serverUrl) }
    var user by remember { mutableStateOf(module.settings.username) }
    var pass by remember { mutableStateOf(module.settings.password) }
    // In-progress text ("Syncing…") and the long linked-folder error detail stay inline — a snackbar is
    // the wrong shape for both. Everything else is announced via [notify].
    var status by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var showResyncConfirm by remember { mutableStateOf(false) }
    var autoSyncEnabled by remember { mutableStateOf(module.settings.autoSyncEnabled) }
    val scope = rememberCoroutineScope()

    /** Announce an outcome. Replaces any showing snackbar so a fast second action isn't queued behind the first. */
    fun notify(message: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }

    val libraryPicker = rememberDirectoryPickerLauncher { dir: PlatformFile? ->
        if (dir != null) {
            module.settings.libraryPath = dir.path
            notify("Library location set. Restart the app to use the new location.")
        }
    }
    // Linked-folder (copy-based) sync picker — Android/SAF. Links the chosen folder and seeds/reconciles it.
    var linkedLabel by remember { mutableStateOf(module.libraryFolder.linkedLabel()) }
    val linkFolderPicker = rememberDirectoryPickerLauncher { dir: PlatformFile? ->
        if (dir != null) {
            busy = true
            status = "Linking folder…"
            scope.launch {
                val result = runCatching { module.linkLibraryFolder(dir) }.getOrElse { e ->
                    println("linkLibraryFolder threw: ${e.message}"); e.printStackTrace()
                    LibraryFolderSyncResult.ERROR
                }
                linkedLabel = module.libraryFolder.linkedLabel()
                val detail = module.libraryFolder.lastError
                // A failure keeps its diagnostic detail inline (multi-line, and worth reading at leisure);
                // a success is a one-liner, which is what a snackbar is for.
                if (result == LibraryFolderSyncResult.ERROR && detail != null) {
                    status = "${folderSyncMessage(result)}\n$detail"
                } else {
                    status = ""
                    notify(folderSyncMessage(result))
                }
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        // Outcomes ("Sync complete", "Folder unlinked") used to be a line of text at the bottom of a long
        // scroll — off-screen, and easy to miss entirely, right when the user wants confirmation.
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding: PaddingValues ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = FORM_WIDTH).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (url.startsWith("http://")) {
                Text(
                    "Using plain HTTP — HTTPS is recommended for security. Plain HTTP only works in debug builds on Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            // Named rather than described, so a user who wants to revoke the saved password knows which
            // OS tool to open (and so "it's in Credential Manager" is verifiable, not a claim).
            Text(
                "Password saved in: ${module.settings.passwordStoreName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !busy,
                onClick = {
                    module.settings.serverUrl = url
                    module.settings.username = user
                    module.settings.password = pass
                    busy = true
                    status = "Syncing…"
                    scope.launch {
                        val message = try {
                            "Sync complete — " + module.sync().summary()
                        } catch (e: Throwable) {
                            "Sync failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                        status = ""
                        notify(message)
                    }
                },
            ) { Text("Sync now") }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Automatic sync", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = {
                        autoSyncEnabled = it
                        module.settings.autoSyncEnabled = it
                        if (!it) module.autoSync.dismissBanner() // clearing the toggle also clears any failure banner
                    },
                )
            }
            Text(
                "Syncs automatically a minute or two after you make changes. Occasional server failures are " +
                    "ignored; if several in a row fail, a banner lets you close it or pause syncing for a day.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(
                enabled = !busy,
                onClick = { showResyncConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Force full re-sync") }
            Text(
                "Deletes everything from one side and force re-syncs from the other — either wiping the local " +
                    "library and pulling from the server, or wiping the server and pushing from this device.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Library location", style = MaterialTheme.typography.titleMedium)
            Text(currentLibraryDir(), style = MaterialTheme.typography.bodySmall)
            if (customLibraryLocationSupported) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { libraryPicker.launch() }) { Text("Choose folder…") }
                    if (module.settings.libraryPath.isNotBlank()) {
                        TextButton(onClick = {
                            module.settings.libraryPath = ""
                            notify("Reverted to the default location. Restart the app to apply.")
                        }) { Text("Use default") }
                    }
                }
                Text(
                    "Recipes and images live in a \"$SALTY_LIBRARY_DIR\" folder in the above location. Must restart app after changing to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (linkedFolderSyncSupported) {
                Text(
                    "Salty Server is the recommended way to keep several devices in sync. As an alternative for backup " +
                        "or one-device-at-a-time use, you can link a folder (e.g. in Nextcloud, OneDrive, or iCloud Drive) " +
                        "that holds a copy of your library in the same \"$SALTY_LIBRARY_DIR\" format Salty for Mac opens " +
                        "directly. The app copies your library to the folder when you leave the app and shortly after " +
                        "edits, and loads a newer copy from the folder when it starts — it does not work live from the " +
                        "folder, so finish on one device before opening the library on another.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (linkedLabel.isNotBlank()) {
                    Text("Linked folder: $linkedLabel", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { linkFolderPicker.launch() }) {
                        Text(if (linkedLabel.isBlank()) "Link folder…" else "Change folder…")
                    }
                    if (linkedLabel.isNotBlank()) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                status = "Syncing to folder…"
                                scope.launch {
                                    val r = runCatching { module.pushLibraryFolder() }
                                        .getOrDefault(LibraryFolderSyncResult.ERROR)
                                    status = ""
                                    notify(folderSyncMessage(r))
                                    busy = false
                                }
                            },
                        ) { Text("Sync to folder now") }
                        TextButton(enabled = !busy, onClick = {
                            module.libraryFolder.unlink()
                            linkedLabel = ""
                            notify("Folder unlinked. The library stays in app storage.")
                        }) { Text("Unlink") }
                    }
                }
            } else {
                Text(
                    "Custom library locations are available on desktop. This device uses its app storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (status.isNotEmpty()) Text(status)
        }
        }
    }

    if (showResyncConfirm) {
        // Run one of the two one-way overwrites, then close the dialog and report the outcome.
        fun startResync(label: String, action: suspend () -> SyncResult) {
            showResyncConfirm = false
            module.settings.serverUrl = url
            module.settings.username = user
            module.settings.password = pass
            busy = true
            status = "$label…"
            scope.launch {
                val message = try {
                    "$label complete — " + action().summary()
                } catch (e: Throwable) {
                    "$label failed: ${e.message}"
                } finally {
                    busy = false
                }
                status = ""
                notify(message)
            }
        }
        AlertDialog(
            onDismissRequest = { showResyncConfirm = false },
            title = { Text("Force full re-sync") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A full re-sync will delete all content from either the local device or the server " +
                            "and force a re-sync from the other direction. We suggest making a database backup " +
                            "before using this option. Please select a force-sync method:",
                    )
                    Button(
                        onClick = { startResync("Pull from server") { module.forceFullResync() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete Local, Pull from Server") }
                    Button(
                        onClick = { startResync("Push from local") { module.forceFullResyncFromLocal() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete Server, Push from Local") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showResyncConfirm = false }) { Text("Cancel") } },
        )
    }
}
