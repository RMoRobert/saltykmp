package com.enuvro.saltykmp

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SoupKitchen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.LibraryClassifier
import com.enuvro.saltykmp.db.LibraryClassifierItem
import com.enuvro.saltykmp.db.LibraryDuplicateFinder
import com.enuvro.saltykmp.db.LibraryDuplicateGroup
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
import com.enuvro.saltykmp.di.LibraryLocationOutcome
import com.enuvro.saltykmp.di.prepareLibraryLocation
import com.enuvro.saltykmp.export.RecipeExportFormat
import com.enuvro.saltykmp.di.linkedFolderSyncSupported
import com.enuvro.saltykmp.search.RecipeSearch
import com.enuvro.saltykmp.text.RecipeListText
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The three classifiers a recipe can be filed under. "Classifier" is this codebase's catch-all for the
 * set — the app has no single user-facing word for them, and calling them "the library" would wrongly
 * suggest the recipes themselves. Each is editable with identical (name) CRUD (mirrors the Swift app).
 */
internal enum class ClassifierKind(
    val title: String,
    val singular: String,
    /** The shared module's equivalent, for the queries and bulk edits that work on any of the three. */
    val classifier: LibraryClassifier,
    /** What this one is for, shown when there are none yet. Ported from the Swift editor. */
    val emptyBody: String,
    /**
     * Leading icon for this kind's sidebar rows — the Material counterpart of the SF Symbol the Swift
     * sidebar labels the same rows with (`fork.knife`, `rectangle.stack`, `tag`). Every row in the
     * drawer carries one, so the labels all share a single indent.
     */
    val icon: ImageVector,
) {
    Courses(
        "Courses", "Course", LibraryClassifier.COURSE,
        "Create courses like \"Main Dish\" or \"Dessert\" to help organize recipes. A recipe belongs to " +
            "at most one course.",
        Icons.Outlined.Restaurant,
    ),
    Categories(
        "Categories", "Category", LibraryClassifier.CATEGORY,
        "Create categories like \"Pasta\" or \"Holiday\" to help organize recipes. A recipe can belong " +
            "to more than one category.",
        Icons.Outlined.Category,
    ),
    Tags(
        "Tags", "Tag", LibraryClassifier.TAG,
        "Create tags like \"quick\" or \"high fiber\" as another way to organize recipes. A recipe can " +
            "have any number of tags.",
        Icons.Outlined.Sell,
    ),
}

/** Display order for the three, so the sidebar's sections and the editor's tabs stay in step. */
internal val CLASSIFIER_ORDER = listOf(ClassifierKind.Categories, ClassifierKind.Courses, ClassifierKind.Tags)

private sealed interface Screen {
    data object List : Screen
    data class Detail(val id: String) : Screen
    /** [imported] seeds a brand-new recipe from the web importer; null for a blank new recipe or an edit. */
    data class Edit(val id: String?, val imported: ImportedRecipe? = null) : Screen
    /** Chef Mode: this recipe with the whole app taken away. See [ChefScreen]. */
    data class Chef(val id: String) : Screen
    data object ManageClassifiers : Screen
    data object ShoppingLists : Screen
    data class ShoppingListDetail(val id: String) : Screen
    data object Settings : Screen
}

// Material 3 scheme from the Material Theme Builder (material-foundation.github.io), with the primary
// family rebuilt by hand around the brand azure #0291FA. Everything else -- secondary, tertiary, error
// and the neutrals -- is the generator's output untouched.
//
// Why the primary family deviates: the builder's default palette style desaturates a vivid source, and
// its azure came back as a slate #39608F. The brand hue is kept here instead, but NOT at the brand's own
// lightness. M3 paints `primary` both as a filled-button background and as foreground text (TextButton
// labels, links, selected states), and #0291FA clears neither -- 3.26:1 on a button, 3.11:1 as text,
// against a 4.5:1 floor. Only the lightness was ever the problem, so the roles below are the same azure
// hue at M3 tones: 45 for light primary (5.35:1 and 5.09:1), 80 for dark. The exact #0291FA still ships
// as surfaceTint and as the light scheme's inversePrimary, where it is decorative rather than load-
// bearing. It is deliberately absent from dark inversePrimary, which it fails at 2.53:1.
//
// surfaceTint is the one role the builder never emits; lightColorScheme/darkColorScheme default it to
// primary, and it stays spelled out because an elevated surface losing its tint is hard to spot.
private val SaltyLightColors = lightColorScheme(
    primary = Color(0xFF026DBD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FE),
    onPrimaryContainer = Color(0xFF004881),
    inversePrimary = Color(0xFF0291FA),
    secondary = Color(0xFF545F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF3C4758),
    tertiary = Color(0xFF68548E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF503D74),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF0291FA),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFEFF0F7),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
)

// The generated dark counterpart: the same three palettes read from their light ends, so primary,
// secondary and tertiary stay legible against the near-black blue-tinted neutral surface.
private val SaltyDarkColors = darkColorScheme(
    primary = Color(0xFFABC7FE),
    onPrimary = Color(0xFF01315B),
    primaryContainer = Color(0xFF004881),
    onPrimaryContainer = Color(0xFFD7E3FE),
    inversePrimary = Color(0xFF026DBD),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD3BCFD),
    onTertiary = Color(0xFF38265C),
    tertiaryContainer = Color(0xFF503D74),
    onTertiaryContainer = Color(0xFFEBDDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceTint = Color(0xFFABC7FE),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3035),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111418),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

// The favorite heart's red. M3 has no scheme role meaning "this is a favorite" — `error` is the only red
// in the scheme and it means something's wrong — so this is a *custom color* in the sense the Material
// Theme Builder uses the term: a hue outside the scheme, harmonized toward the source (a pure red pulled
// toward the brand azure lands in crimson rather than fire-engine), shipped as a light/dark tonal pair at
// the tones M3 gives error itself, 40 and 80.
//
// Both sides clear WCAG 1.4.11's 3:1 floor for meaningful non-text content against every surface the
// heart is painted on. Worst case either way is a selected row's secondaryContainer: 5.27:1 light,
// 5.54:1 dark. Against the plain list surface it's 6.48:1 and 10.88:1. Colour is never the only channel
// (WCAG 1.4.1) — the heart's shape and its "Favorite" contentDescription both carry the meaning on their
// own, so the red is recognition, not information.
private val FavoriteRedLight = Color(0xFFB3143C)
private val FavoriteRedDark = Color(0xFFFFB2BF)

/** Reads the favorite red for the active theme; provided by [SaltyTheme] alongside the color scheme. */
private val LocalFavoriteColor = staticCompositionLocalOf { FavoriteRedLight }

@Composable
private fun SaltyTheme(density: UiDensity, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val metrics = density.metrics
    val outerDensity = LocalDensity.current
    // Recomputed only when the factor changes, which in practice is once: the ramp is 30 TextStyles and
    // there is no reason to rebuild it on every recomposition of the whole app.
    val typography = remember(metrics.headingScale) { Typography().scaleHeadings(metrics.headingScale) }
    MaterialTheme(
        colorScheme = if (dark) SaltyDarkColors else SaltyLightColors,
        shapes = metrics.shapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalFavoriteColor provides if (dark) FavoriteRedDark else FavoriteRedLight,
            LocalUiDensity provides density,
            // Shrinks every dp in the tree at once — M3's own internal padding included, which is the
            // only way to reach a text field's hardcoded 56dp minimum without touching 25 call sites.
            // fontScale takes the reciprocal so type comes out pixel-identical: an sp renders at
            // `sp x density x fontScale`, so the two factors cancel and only layout tightens.
            LocalDensity provides outerDensity.scaledBy(metrics.layoutScale),
            // Read by Checkbox, Switch, RadioButton, IconButton and both text fields to pad an invisible
            // touch target around themselves. Shrinking it takes the dead space out without changing any
            // control's visible size — the whole of the compact layout win, at one line.
            LocalMinimumInteractiveComponentSize provides metrics.minInteractiveSize,
        ) {
            // Most screens sit on a Scaffold, which paints its own background — but the empty-pane
            // placeholders and the startup spinner don't, and on desktop the bare window behind them is
            // white whatever the theme says. One Surface under everything keeps dark mode dark.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
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

    /**
     * Back out of the current sub-screen. A shopping list returns to the lists, and Chef Mode to the
     * recipe it was started from — in both cases the thing one level up, not all the way out.
     */
    fun goBack() {
        screen = when (val current = screen) {
            is Screen.ShoppingListDetail -> Screen.ShoppingLists
            is Screen.Chef -> Screen.Detail(current.id)
            else -> Screen.List
        }
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
                AppCommand.FindInList -> if (screen != Screen.Settings && screen != Screen.ManageClassifiers) {
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

    val uiDensity by module.uiDensity.collectAsState()

    SaltyTheme(uiDensity) {
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
            StartupPhase.Ready -> {
                val current = screen
                // Chef Mode replaces the shell rather than sitting inside it: taking the app away is
                // the whole feature, so it is composed here — above the auto-sync banner, the
                // navigation and the two-pane split — instead of as another case in [AppContent].
                if (current is Screen.Chef) {
                    ChefScreen(module, current.id, onExit = { goBack() })
                } else {
                    AppContent(
                        module = module,
                        screen = current,
                        onScreen = { screen = it },
                        recipeFilter = recipeFilter,
                        onRecipeFilter = { recipeFilter = it },
                        snackbarHost = snackbarHost,
                        onBack = { goBack() },
                        findRequest = findRequest,
                        onImportFromWeb = { showWebImport = true },
                    )
                }
            }
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
    /** Report an outcome with no action attached (an export finished, a save failed). Blank = say nothing. */
    val notify: (message: String) -> Unit,
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

    /** Say [message], unless it's blank — a cancelled save dialog has nothing worth reporting. */
    fun notify(message: String) {
        if (message.isBlank()) return
        snackbarScope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
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
        notify = ::notify,
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
            onChefMode = { shell.onScreen(Screen.Chef(s.id)) },
            onFilter = { shell.openFilter(it) },
            onDeleted = shell.showUndo,
            onNotify = shell.notify,
        )
        is Screen.Edit -> RecipeEditScreen(
            shell.module, s.id, s.imported,
            onDone = { savedId -> shell.onScreen(savedId?.let { Screen.Detail(it) } ?: Screen.List) },
        )
        Screen.ManageClassifiers -> ClassifierEditScreen(shell.module, onBack = shell.onBack)
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
        // Chef Mode never reaches a layout: [App] composes it in place of the whole shell, so by the
        // time either layout runs the screen cannot be this one.
        is Screen.Chef -> Unit
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
                    onChefMode = { shell.onScreen(Screen.Chef(s.id)) },
                    onFilter = { shell.openFilter(it) },
                    onDeleted = shell.showUndo,
                    onNotify = shell.notify,
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
                            icon = Icons.AutoMirrored.Outlined.ListAlt,
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
        // Settings and the classifier editor are app-level, not a slice of the library, so they take the
        // whole content area (the navigation beside them stays put) rather than a detail pane.
        Screen.ManageClassifiers -> ClassifierEditScreen(shell.module, onBack = shell.onBack)
        Screen.Settings -> SettingsScreen(shell.module, onBack = shell.onBack)
        // Chef Mode never reaches a layout: [App] composes it in place of the whole shell, so by the
        // time either layout runs the screen cannot be this one.
        is Screen.Chef -> Unit
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
            onChefMode = { shell.onScreen(Screen.Chef(screen.id)) },
            onFilter = { shell.openFilter(it) },
            onDeleted = shell.showUndo,
            onNotify = shell.notify,
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
                Icon(Icons.Outlined.Menu, contentDescription = "Open menu")
            }
        },
    ) {
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.All,
            onClick = { shell.openFilter(RecipeFilter.All) },
            icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
            label = { Text("Recipes") },
        )
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.Favorites,
            onClick = { shell.openFilter(RecipeFilter.Favorites) },
            // Hollow here and on the bookmark below, for the reason spelled out in SaltyDrawerContents.
            icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
            label = { Text("Favorites") },
        )
        NavigationRailItem(
            selected = shell.onRecipes && shell.filter is RecipeFilter.WantToMake,
            onClick = { shell.openFilter(RecipeFilter.WantToMake) },
            icon = { Icon(Icons.Outlined.BookmarkAdded, contentDescription = null) },
            label = { Text("To Make") },
        )
        NavigationRailItem(
            selected = shell.screen is Screen.ShoppingLists || shell.screen is Screen.ShoppingListDetail,
            onClick = { shell.onScreen(Screen.ShoppingLists) },
            icon = { Icon(Icons.Outlined.ShoppingCart, contentDescription = null) },
            label = { Text("Shopping") },
        )
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = shell.screen is Screen.Settings,
            onClick = { shell.onScreen(Screen.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
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
 * Delete a recipe and offer it back through [showUndo].
 *
 * Shared by the list row's menu and the detail screen's toolbar so the undo stays correct in both: the
 * image state (filename, thumbnail blob, image timestamp) lives on the DB row rather than in the wire
 * shape, so restoring the recipe alone would bring it back without its photo — and the tombstone has to
 * go too, or the next sync would faithfully re-delete the recipe the user just got back.
 */
private fun deleteRecipeWithUndo(
    module: AppModule,
    id: String,
    showUndo: (message: String, undo: () -> Unit) -> Unit,
) {
    val deleted = module.localStore.recipeForUpload(id) ?: return
    val row = module.repository.recipe(id)
    module.localStore.deleteRecipe(id)
    module.onLocalChange()
    showUndo("Deleted \"${deleted.name}\"") {
        module.localStore.upsertRecipe(deleted)
        module.localStore.setRecipeImage(id, row?.imageFilename, row?.imageThumbnailData, row?.lastModifiedImageDate)
        module.localStore.clearRecipeTombstones(listOf(id))
        module.onLocalChange()
    }
}

/**
 * Format picker for exporting one recipe (the Swift app's Export… submenu).
 *
 * A dialog rather than a nested menu: Material's DropdownMenu has no submenus, and the three formats
 * are not self-explanatory from their names — each needs the line of description that says which one to
 * pick. [onPick] is handed the choice; the caller does the work and reports the outcome.
 */
@Composable
private fun RecipeExportDialog(
    recipeName: String,
    onPick: (RecipeExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
        title = { Text("Export \"$recipeName\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RecipeExportFormat.entries.forEach { format ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(format) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        Text(format.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            format.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        // No confirm button: picking a format IS the confirmation, and a second "Export" step would
        // only add a tap.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                    Icon(Icons.Outlined.Menu, contentDescription = "Open menu")
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
    // The recipe whose Export… dialog is open, as (id, name). Held by the pane rather than the row so
    // it survives the row menu closing — and so the name is still there to title the dialog after the
    // recipe scrolls out of the composition.
    var exporting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportScope = rememberCoroutineScope()

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
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
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
                                Icon(Icons.Outlined.FilterList, contentDescription = "Search options")
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
                                            { Icon(Icons.Outlined.Check, contentDescription = null) }
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
                            Icon(Icons.Outlined.Close, contentDescription = "Close search")
                        }
                        return@TopAppBar
                    }
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            RecipeSort.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    leadingIcon = if (opt == sort) {
                                        { Icon(Icons.Outlined.Check, contentDescription = null) }
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
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
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
                Icon(Icons.Outlined.Add, contentDescription = "New recipe")
            }
        },
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    query.isNotBlank() -> EmptyState(
                        icon = Icons.Outlined.Search,
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
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(sorted, key = { it.id }) { recipe ->
                        // Prefer the cached thumbnail blob; fall back to the full image for rows synced
                        // before thumbnail caching (a re-sync backfills the blob).
                        val thumb = remember(recipe.imageThumbnailData, recipe.imageFilename) {
                            (recipe.imageThumbnailData
                                ?: recipe.imageFilename?.let { module.imageFiles.load(it) })
                                ?.let { decodeImageBitmap(it) }
                        }
                        var rowMenu by remember(recipe.id) { mutableStateOf(false) }
                        var rowLastMadeMenu by remember(recipe.id) { mutableStateOf(false) }
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
                                headlineContent = { Text(recipe.name) },
                                supportingContent = subtitle?.let { { Text(it) } },
                                // Favorite belongs on the trailing edge (as in the SwiftUI app): prefixed to
                                // the headline it read as the first character of the recipe's name. A heart,
                                // not a star, because stars already mean the 1-5 rating here — and "favorite"
                                // is what Material's icon set calls this glyph.
                                // 20dp rather than the 24dp M3 gives a trailing icon: this is a status
                                // marker, not an action, and at full size it competed with the thumbnail.
                                trailingContent = if (recipe.isFavorite == true) {
                                    {
                                        Icon(
                                            Icons.Filled.Favorite,
                                            contentDescription = "Favorite",
                                            tint = LocalFavoriteColor.current,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
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
                            // Long-press acts on the row without opening it — the same set the Swift
                            // app's context menu offers. It used to open straight into the Last Made
                            // menu, which made that the only thing a long press could ever do.
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = { rowMenu = false; shell.onScreen(Screen.Edit(recipe.id)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export…") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = { rowMenu = false; exporting = recipe.id to recipe.name },
                                )
                                DropdownMenuItem(
                                    text = { Text("Last Made…") },
                                    leadingIcon = { Icon(Icons.Outlined.EventAvailable, contentDescription = null) },
                                    onClick = { rowMenu = false; rowLastMadeMenu = true },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    // No confirmation: reaching this took a long press and a deliberate
                                    // tap on an item marked destructive, and the Undo snackbar is the
                                    // way back — the same trade the shopping lists' swipe-to-delete makes.
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        rowMenu = false
                                        // In a split view the detail pane reads its recipe once, so
                                        // deleting the one it's showing would leave it on screen. Send
                                        // the pane back to its placeholder first.
                                        if (recipe.id == selectedId) shell.onScreen(Screen.List)
                                        deleteRecipeWithUndo(module, recipe.id, shell.showUndo)
                                    },
                                )
                            }
                            LastMadeMenu(
                                expanded = rowLastMadeMenu,
                                currentLastPrepared = recipe.lastPrepared,
                                onDismiss = { rowLastMadeMenu = false },
                                onSet = { wire ->
                                    module.localStore.setRecipePrepared(recipe.id, wire, nowTimestamp())
                                    module.onLocalChange()
                                },
                            )
                        }
                        HorizontalDivider()
                    }
                }
                EdgeScrollbar(listState)
            }
        }
    }

    exporting?.let { (id, name) ->
        RecipeExportDialog(
            recipeName = name,
            onDismiss = { exporting = null },
            onPick = { format ->
                exporting = null
                // The save dialog / share sheet suspends until the user is done with it, so this runs
                // for as long as they take. Reading and rendering the recipe is fast; the wait is theirs.
                exportScope.launch { shell.notify(RecipeExporter.export(module, id, format)) }
            },
        )
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

        // Fixed destinations first. Every row in the drawer — these and the classifier entries below —
        // carries a leading icon, so all the labels share one indent instead of stepping in and out.
        // The section headers stay text-only, which is both M3's drawer anatomy and what Swift does.
        DrawerDestination(
            icon = Icons.Outlined.MenuBook,
            label = "All Recipes",
            selected = selected is RecipeFilter.All,
            onClick = { go(RecipeFilter.All) },
        )
        // Hollow. A destination row is a place to go, not a readout, so filling it would spend the one
        // signal this app reserves for state: a solid heart means "this recipe is a favorite" on a list
        // row and a detail badge, and it should mean nothing else. (MenuBook above is the exception, and
        // not by choice — Icons.Outlined.MenuBook is byte-identical to the filled one, so its left page
        // is solid whatever you import. It's kept for the open-book shape, which is what iOS shows.)
        // Note these are the *Border* icons — Icons.Outlined.Favorite is still a solid heart.
        DrawerDestination(
            icon = Icons.Outlined.FavoriteBorder,
            label = "Favorites",
            selected = selected is RecipeFilter.Favorites,
            onClick = { go(RecipeFilter.Favorites) },
        )
        DrawerDestination(
            icon = Icons.Outlined.BookmarkAdded,
            label = "Want to Make",
            selected = selected is RecipeFilter.WantToMake,
            onClick = { go(RecipeFilter.WantToMake) },
        )
        DrawerDestination(
            icon = Icons.Outlined.ShoppingCart,
            label = "Shopping Lists",
            selected = shell.screen is Screen.ShoppingLists || shell.screen is Screen.ShoppingListDetail,
            onClick = { shell.onScreen(Screen.ShoppingLists); onNavigated() },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Categories/Courses/Tags, each collapsible. Sections render even when empty so the header
        // still says the section exists.
        for (kind in CLASSIFIER_ORDER) {
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
                        icon = { Icon(kind.icon, contentDescription = null) },
                        label = { Text(name) },
                        selected = isSelected,
                        onClick = { go(filter) },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .height(LocalUiDensity.current.metrics.drawerRowHeight),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // Editing the classifiers is rare and app-level, so it sits down here with Settings as one row
        // rather than an "Edit …" row under each of the three sections above. It's a plain destination:
        // which of the three you're editing is a tab inside the editor, not a menu hung off this row.
        DrawerDestination(
            icon = Icons.Outlined.Edit,
            label = "Edit Classifiers",
            selected = shell.screen == Screen.ManageClassifiers,
            onClick = { shell.onScreen(Screen.ManageClassifiers); onNavigated() },
        )
        // Settings is app-level and infrequent, so it lives here rather than taking a slot in the
        // recipe list's app bar (which is for actions on the list itself).
        DrawerDestination(
            icon = Icons.Outlined.Settings,
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
        // .height before M3's internal heightIn(min = 56.dp), which is what lets it win. See
        // [DensityMetrics.drawerRowHeight].
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .height(LocalUiDensity.current.metrics.drawerRowHeight),
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
            if (collapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
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

/** Zoom ceiling for the full-image viewer; the same 4x the Swift viewer stops at. */
private const val FULL_IMAGE_MAX_SCALE = 4f

/**
 * Full-screen viewer for a recipe's image, opened by tapping the image on the detail screen — the
 * Compose counterpart of the Swift app's tap-to-enlarge sheet.
 *
 * Pinch or double-tap zooms up to [FULL_IMAGE_MAX_SCALE] and dragging pans, clamped to the picture's own
 * edges so it can never be dragged off into the letterbox bands. The on-screen zoom controls (and the
 * +/-/0 keys) are there for desktop, which has no pinch gesture and would otherwise be stuck at 1x.
 */
@Composable
private fun RecipeFullImageDialog(image: ImageBitmap, title: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // The viewer *is* the whole surface, so it opts out of the platform's inset dialog width.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(Size.Zero) }
        // The size the image is actually painted at under ContentScale.Fit. The pan limits come from this
        // rather than from the viewport, or a portrait photo in a landscape window could be dragged well
        // past its own edge into the empty bands beside it.
        val drawn by remember(image) {
            derivedStateOf {
                if (viewport.minDimension <= 0f) {
                    Size.Zero
                } else {
                    val f = min(viewport.width / image.width, viewport.height / image.height)
                    Size(image.width * f, image.height * f)
                }
            }
        }
        val scope = rememberCoroutineScope()
        var zoomAnimation by remember { mutableStateOf<Job?>(null) }

        /** A discrete zoom (double-tap, a button, a key) animates; a live pinch writes the state straight through. */
        fun animateZoom(target: Float, anchor: Offset) {
            zoomAnimation?.cancel()
            val to = target.coerceIn(1f, FULL_IMAGE_MAX_SCALE)
            val fromScale = scale
            val fromOffset = offset
            val toOffset = clampToImage(pinnedOffset(anchor, offset, viewport, to / fromScale), to, drawn, viewport)
            zoomAnimation = scope.launch {
                animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                    scale = lerp(fromScale, to, t)
                    offset = Offset(lerp(fromOffset.x, toOffset.x, t), lerp(fromOffset.y, toOffset.y, t))
                }
            }
        }

        // Reading `scale` straight into the composition would recompose the controls on every frame of a
        // pinch; these only change when a button actually flips between enabled and disabled.
        val canZoomOut by remember { derivedStateOf { scale > 1f } }
        val canZoomIn by remember { derivedStateOf { scale < FULL_IMAGE_MAX_SCALE } }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier
                .fillMaxSize()
                // Fully opaque, not a translucent scrim: the recipe text showing through behind a
                // photo is busy, and black is the ground every photo viewer puts under an image.
                .background(MaterialTheme.colorScheme.scrim)
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                    when (event.key) {
                        Key.Equals, Key.Plus, Key.NumPadAdd -> { animateZoom(scale * 1.25f, centre); true }
                        Key.Minus, Key.NumPadSubtract -> { animateZoom(scale / 1.25f, centre); true }
                        Key.Zero, Key.NumPad0 -> { animateZoom(1f, centre); true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        zoomAnimation?.cancel()
                        val next = (scale * zoom).coerceIn(1f, FULL_IMAGE_MAX_SCALE)
                        val moved = pinnedOffset(centroid, offset, viewport, next / scale) + pan
                        scale = next
                        offset = clampToImage(moved, next, drawn, viewport)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at -> animateZoom(if (scale > 1f) 1f else 2f, at) })
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = title,
                // Read in the layer block, not in composition: a pinch then only redraws, never recomposes.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The chrome floats over the photo, so it needs its own ground to stay readable against
                // whatever happens to be behind it.
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    // Spelled out because a translucent colour no longer matches a scheme role, so
                    // Surface can't work the content colour out for itself.
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                FilledTonalIconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { animateZoom(scale / 1.25f, centre) }, enabled = canZoomOut) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { animateZoom(1f, centre) }, enabled = canZoomOut) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reset zoom")
                    }
                    IconButton(onClick = { animateZoom(scale * 1.25f, centre) }, enabled = canZoomIn) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom in")
                    }
                }
            }
        }
    }
}

/**
 * The translation that keeps whatever sits under [anchor] pinned there while the view scales by [zoom].
 * Without it a pinch drifts away from the fingers, and a double-tap zooms the middle of the window rather
 * than the thing that was tapped.
 */
private fun pinnedOffset(anchor: Offset, offset: Offset, viewport: Size, zoom: Float): Offset {
    val centre = Offset(viewport.width / 2f, viewport.height / 2f)
    return (anchor - centre) * (1f - zoom) + offset * zoom
}

/** Holds [offset] to the range where the scaled image still covers the viewport, so no edge pulls inside it. */
private fun clampToImage(offset: Offset, scale: Float, drawn: Size, viewport: Size): Offset {
    val maxX = ((drawn.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((drawn.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
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
    /** Hand this recipe to Chef Mode — the whole app goes away and only the cooking is left. */
    onChefMode: () -> Unit,
    onFilter: (RecipeFilter) -> Unit,
    onDeleted: (message: String, undo: () -> Unit) -> Unit,
    /** Report an export's outcome; blank means there is nothing to say (a cancelled save dialog). */
    onNotify: (String) -> Unit,
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
    var showExport by remember(id) { mutableStateOf(false) }
    var showFullImage by remember(id) { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Capped, because this bar carries four actions on a phone and a long name would
                    // otherwise wrap to three lines and make the bar taller than the recipe's image.
                    Text(recipe?.name ?: "Recipe", maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    // No back arrow in a two-pane layout: the list it would return to is still on screen.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (recipe != null) {
                        // First in the row, because cooking the recipe is what the recipe is for —
                        // and it is the one action here that isn't reachable from anywhere else.
                        IconButton(onClick = onChefMode) {
                            Icon(Icons.Outlined.SoupKitchen, contentDescription = "Chef Mode")
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                // The list's long-press is the only other route to these, and a long press
                                // is not something anyone discovers — so the recipe carries them too.
                                DropdownMenuItem(
                                    text = { Text("Export…") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = { overflowMenu = false; showExport = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Last Made…") },
                                    leadingIcon = { Icon(Icons.Outlined.EventAvailable, contentDescription = null) },
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
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxSize().verticalScroll(scroll),
                contentAlignment = Alignment.TopCenter,
            ) {
                // One container over the whole recipe, so a single drag can select across the sections —
                // ingredients through directions — rather than each block being its own island of text.
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = READING_WIDTH).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        image?.let {
                            Image(
                                bitmap = it,
                                contentDescription = recipe.name,
                                // Wide layouts get a hero across the pane; a phone keeps the compact square, where a
                                // 240dp-tall image would push everything else below the fold. Either way it is
                                // cropped, so tapping it opens the whole picture (as the Swift app does).
                                modifier = (if (wide) {
                                    Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp))
                                } else {
                                    Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
                                }).clickable(onClickLabel = "View full image") { showFullImage = true },
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
                        // Each flag carries its own tint so the favorite heart is the same red here as in
                        // the list — one colour for one meaning, wherever it shows up.
                        val favoriteRed = LocalFavoriteColor.current
                        val flags = buildList {
                            if (recipe.isFavorite == true) {
                                add(Triple(Icons.Filled.Favorite, "Favorite", favoriteRed))
                            }
                            if (recipe.wantToMake == true) {
                                add(Triple(Icons.Filled.BookmarkAdded, "Want to Make", MaterialTheme.colorScheme.primary))
                            }
                        }
                        if (metaItems.isNotEmpty() || flags.isNotEmpty()) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    metaItems.forEach { (k, v) -> DetailMeta(k, v) }
                                    if (flags.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            flags.forEach { (icon, label, tint) ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        icon,
                                                        contentDescription = null,
                                                        tint = tint,
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
            EdgeScrollbar(scroll)
        }
    }

    if (showFullImage && image != null) {
        RecipeFullImageDialog(
            image = image,
            title = recipe?.name.orEmpty(),
            onDismiss = { showFullImage = false },
        )
    }

    if (showExport && recipe != null) {
        RecipeExportDialog(
            recipeName = recipe.name,
            onDismiss = { showExport = false },
            onPick = { format ->
                showExport = false
                exportScope.launch { onNotify(RecipeExporter.export(module, id, format)) }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete recipe?") },
            text = { Text("\"${recipe?.name.orEmpty()}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onClose()
                    // Deleting propagates to the server and every other device, so it comes back with an
                    // Undo — see deleteRecipeWithUndo for what restoring has to put back.
                    deleteRecipeWithUndo(module, id, onDeleted)
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
                    IconButton(onClick = cancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                },
                actions = {
                    IconButton(onClick = save, enabled = name.isNotBlank()) {
                        Icon(Icons.Outlined.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        // Form fields stretched across a desktop window are hard to scan; cap and centre the column.
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxSize().verticalScroll(scroll),
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
            EdgeScrollbar(scroll)
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
// clean), so the flag is carried on each row and round-trips through a save untouched. The ingredient
// "main" flag does have a per-row toggle — the medal in SectionRow — matching the other two clients.
private data class IngredientRow(val id: String, val text: String = "", val isHeading: Boolean = false, val isMain: Boolean = false)
private data class DirectionRow(val id: String, val text: String = "", val isHeading: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientEditList(items: SnapshotStateList<IngredientRow>) {
    var bulkEdit by remember { mutableStateOf(false) }
    EditSectionHeader("Ingredients") {
        TextButton(onClick = { bulkEdit = true }) { Text("Edit as text") }
    }
    if (bulkEdit) {
        BulkTextEditDialog(
            title = "Edit Ingredients",
            help = "Each line represents one ingredient (or heading). Add a blank line before any lines that are to be " +
                    "interpreted as headings, or end those lines with a colon. Ingredient lines ending with \"[*]\"" +
                    "(no quotes) will be marked as main ingredients. Select \"Clean Up\" to remove common list delimiter " +
                    "characters and trim whitespace.",
            initialText = RecipeListText.formatIngredients(
                items.map { Ingredient(it.id, isHeading = it.isHeading, isMain = it.isMain, text = it.text) },
            ),
            // Unlike directions, don't strip numbers from ingredients since usually start with number
            stripNumbering = false,
            onDismiss = { bulkEdit = false },
            onSave = { text ->
                bulkEdit = false
                val parsed = RecipeListText.parseIngredients(text)
                items.clear()
                parsed.forEach { items.add(IngredientRow(it.id, it.text, it.isHeading, it.isMain)) }
            },
        )
    }
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
            isMain = item.isMain,
            onMainChange = { items[i] = item.copy(isMain = it) },
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
    var bulkEdit by remember { mutableStateOf(false) }
    EditSectionHeader("Directions") {
        TextButton(onClick = { bulkEdit = true }) { Text("Edit as text") }
    }
    if (bulkEdit) {
        BulkTextEditDialog(
            title = "Edit Directions",
            help = "Edit directions as plain text. Each line represents one direction step. Single blank lines " +
                    "separate directions. Use double blank lines before any lines that are to be interpreted as " +
                    "section headings, or end those lines with a colon. Select \"Clean Up\" to remove list delimiters " +
                    "and trim whitespace.",
            initialText = RecipeListText.formatDirections(
                items.map { Direction(it.id, isHeading = it.isHeading, text = it.text) },
            ),
            stripNumbering = true,
            onDismiss = { bulkEdit = false },
            onSave = { text ->
                bulkEdit = false
                val parsed = RecipeListText.parseDirections(text)
                items.clear()
                parsed.forEach { items.add(DirectionRow(it.id, it.text, it.isHeading == true)) }
            },
        )
    }
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
 * read as section titles even though there's no per-row toggle. [number] gutters a step number. Passing
 * [onMainChange] adds the ingredient "main" toggle to the row; directions leave it null. */
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
    isMain: Boolean = false,
    onMainChange: ((Boolean) -> Unit)? = null,
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
        // Salty's "main ingredient" marker: a medal, filled once set and outlined while not, so the state
        // reads without comparing two rows. Not a star — stars are the 1-5 rating in this app. A heading
        // can't be a main ingredient, but it keeps the column so its field doesn't run wider than the
        // ingredients beneath it.
        if (onMainChange != null) {
            if (isHeading) {
                Spacer(Modifier.width(48.dp))
            } else {
                IconToggleButton(checked = isMain, onCheckedChange = onMainChange) {
                    Icon(
                        if (isMain) Icons.Filled.WorkspacePremium else Icons.Outlined.WorkspacePremium,
                        contentDescription = "Main ingredient",
                        tint = if (isMain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        // A mistyped order used to mean retyping every row below it; these move the row instead. Half-height
        // so the pair takes no more width than one icon button.
        Column {
            RowMoveButton(Icons.Outlined.KeyboardArrowUp, "Move up", canMoveUp, onMoveUp)
            RowMoveButton(Icons.Outlined.KeyboardArrowDown, "Move down", canMoveDown, onMoveDown)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remove") }
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
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
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

/**
 * Edits a whole ingredient or direction list as one block of text (the Swift app's bulk edit sheets).
 *
 * Row-at-a-time entry is slow on a phone and hopeless for a recipe pasted from somewhere else, which is
 * the case this exists for. The grammar is [RecipeListText]'s, and it's not guessable, so the help text
 * is one tap away rather than buried — and the box is monospaced, since the format is whitespace-
 * significant and proportional type hides exactly the blank lines that carry the meaning.
 *
 * Local state only: the caller's list is replaced on Save and untouched otherwise, so Cancel is free.
 */
@Composable
private fun BulkTextEditDialog(
    title: String,
    help: String,
    initialText: String,
    /** Whether "Clean up" also strips leading "1." / "2)" — true for directions, never for ingredients. */
    stripNumbering: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded once: re-seeding on recomposition would fight the user's typing.
    var text by remember { mutableStateOf(initialText) }
    var showHelp by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title)
                IconButton(onClick = { showHelp = !showHelp }) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Formatting help")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showHelp) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                )
                TextButton(
                    onClick = { text = RecipeListText.cleanUp(text, stripNumbering) },
                    enabled = text.isNotBlank(),
                ) { Text("Clean up") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A rule and a bold label between blocks of the (long, single-column) recipe form. [action] hangs an
 * optional control off the right-hand end, for a section that can be edited a second way.
 */
@Composable
private fun EditSectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    if (action == null) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            action()
        }
    }
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
                IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove") }
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
            if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
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

/**
 * Add / rename / delete the library classifiers (courses, categories, and tags) — the KMP equivalent of
 * the Swift app's LibraryClassifiersEditView. All three are the same editor over a different list, so
 * they're one destination with a tab apiece rather than three places to navigate to. Edits are written
 * locally with a fresh lastModifiedDate; the next sync pushes them to the server.
 *
 * Beyond one-at-a-time editing there is a Select mode, entered from the app bar or by long-pressing a
 * row, the way Notes and Photos do it. It exists for the two jobs that are miserable one row at a time:
 * clearing out a drift of unused tags, and folding "Deserts" / "desserts" / "Dessert " back into one —
 * the same fold [LibraryDuplicateMerger] runs automatically for same-named rows, offered here for the
 * ones only a human can tell are the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassifierEditScreen(module: AppModule, onBack: () -> Unit) {
    // Which of the three is showing is state within this screen, not a destination of its own.
    var kind by remember { mutableStateOf(CLASSIFIER_ORDER.first()) }

    val items by remember(kind) { module.repository.classifierItems(kind.classifier) }
        .collectAsState(initial = emptyList())

    // Search is opt-in and replaces the title, as on the recipe list: closing it clears the query, so
    // the list can never stay silently filtered by something the user can't see.
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Select mode gets its own app bar rather than dimmed extra buttons on the normal one, and takes
    // the tab row with it: switching classifiers mid-selection would strand ids that only mean
    // anything in the tab they came from.
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }

    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryClassifierItem?>(null) }
    // Rows each confirmation is about, captured when it opens: the list under it keeps updating (a
    // sync can land mid-dialog), and both actions have to act on what the user was actually shown.
    var pendingDeletion by remember { mutableStateOf(emptyList<LibraryClassifierItem>()) }
    var mergeCandidates by remember { mutableStateOf(emptyList<LibraryClassifierItem>()) }
    var mergeSurvivorId by remember { mutableStateOf<String?>(null) }

    val shown = remember(items, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) items else items.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    fun leaveSelectMode() {
        selecting = false
        selection = emptySet()
    }

    fun show(next: ClassifierKind) {
        if (next == kind) return
        kind = next
        leaveSelectMode()
    }

    fun save(id: String, name: String) {
        val ts = nowTimestamp()
        when (kind) {
            ClassifierKind.Courses -> module.localStore.upsertCourse(ServerCourse(id, name, ts))
            ClassifierKind.Categories -> module.localStore.upsertCategory(ServerCategory(id, name, ts))
            ClassifierKind.Tags -> module.localStore.upsertTag(ServerTag(id, name, ts))
        }
        module.onLocalChange()
    }

    /** Opens the delete confirmation. Every delete is confirmed: one that asks only sometimes is one you stop reading. */
    fun requestDeletion(ids: Set<String>) {
        val rows = items.filter { it.id in ids }
        if (rows.isNotEmpty()) pendingDeletion = rows
    }

    fun confirmDeletion() {
        val rows = pendingDeletion
        pendingDeletion = emptyList()
        if (rows.isEmpty()) return
        module.classifierEditor.delete(kind.classifier, rows.map { it.id })
        module.onLocalChange()
        leaveSelectMode()
    }

    /**
     * Opens the merge sheet, pre-picking the survivor the duplicate scan would have chosen (most
     * recipes, ties to the oldest row) — so the common case is one tap, with the name that survives
     * still on screen to be changed.
     */
    fun requestMerge() {
        val rows = LibraryDuplicateFinder.ranked(items.filter { it.id in selection })
        // Two is the minimum: merging needs something to merge into.
        if (rows.size < 2) return
        mergeCandidates = rows
        mergeSurvivorId = rows.first().id
    }

    fun cancelMerge() {
        mergeCandidates = emptyList()
        mergeSurvivorId = null
    }

    fun confirmMerge() {
        val rows = mergeCandidates
        val survivor = rows.firstOrNull { it.id == mergeSurvivorId }
        val losing = rows.filter { it.id != survivor?.id }
        cancelMerge()
        if (survivor == null || losing.isEmpty()) return
        module.classifierMerger.merge(listOf(LibraryDuplicateGroup(kind.classifier, survivor, losing)))
        module.onLocalChange()
        leaveSelectMode()
    }

    Scaffold(
        topBar = {
            Column {
                if (selecting) {
                    TopAppBar(
                        // Counting what's selected, not naming the screen: in this mode the bar is
                        // reporting state, which is the whole reason it replaces the normal one.
                        title = { Text(if (selection.isEmpty()) "Select ${kind.title.lowercase()}" else "${selection.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = { leaveSelectMode() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Done selecting")
                            }
                        },
                        actions = {
                            IconButton(onClick = { requestMerge() }, enabled = selection.size > 1) {
                                Icon(Icons.Outlined.Merge, contentDescription = "Merge")
                            }
                            IconButton(onClick = { requestDeletion(selection) }, enabled = selection.isNotEmpty()) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                            }
                        },
                        // A different container colour is what tells you at a glance that the screen is
                        // in a mode — the icons alone read as an ordinary app bar.
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (searchActive) {
                                val focus = remember { FocusRequester() }
                                LaunchedEffect(Unit) { focus.requestFocus() }
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = { Text("Search ${kind.title.lowercase()}") },
                                    singleLine = true,
                                    trailingIcon = if (query.isNotEmpty()) {
                                        {
                                            IconButton(onClick = { query = "" }) {
                                                Icon(Icons.Outlined.Close, contentDescription = "Clear search")
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
                                        // Escape closes search, as it does in every desktop search field.
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
                                Text("Edit Classifiers")
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (searchActive) {
                                IconButton(onClick = { searchActive = false; query = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Close search")
                                }
                            } else {
                                IconButton(onClick = { searchActive = true }, enabled = items.isNotEmpty()) {
                                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                                }
                                IconButton(onClick = { selecting = true }, enabled = items.isNotEmpty()) {
                                    Icon(Icons.Outlined.Checklist, contentDescription = "Select")
                                }
                                IconButton(onClick = { adding = true }) {
                                    Icon(Icons.Outlined.Add, contentDescription = "New ${kind.singular}")
                                }
                            }
                        },
                    )
                    PrimaryTabRow(selectedTabIndex = CLASSIFIER_ORDER.indexOf(kind)) {
                        for (tab in CLASSIFIER_ORDER) {
                            Tab(
                                selected = tab == kind,
                                onClick = { show(tab) },
                                text = { Text(tab.title) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (query.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matches",
                        body = "No ${kind.title.lowercase()} match \"${query.trim()}\".",
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Edit,
                        title = "No ${kind.title.lowercase()} yet",
                        body = kind.emptyBody,
                        actionLabel = "New ${kind.singular}",
                        onAction = { adding = true },
                    )
                }
            }
        } else {
            // Keyed on the tab: a scroll offset carried over from another list would mean nothing here.
            val listState = remember(kind) { LazyListState() }
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(shown, key = { it.id }) { item ->
                        val checked = item.id in selection
                        ListItem(
                            modifier = Modifier
                                .widthIn(max = FORM_WIDTH)
                                // The Checkbox is decorative (onCheckedChange = null; the row owns the
                                // tap), so the row has to carry the checked state itself or a screen
                                // reader announces the name with no hint that it's selected.
                                .semantics {
                                    if (selecting) toggleableState = ToggleableState(checked)
                                }
                                .combinedClickable(
                                    role = if (selecting) Role.Checkbox else null,
                                    onClick = {
                                        if (selecting) {
                                            selection = if (checked) selection - item.id else selection + item.id
                                        } else {
                                            renaming = item
                                        }
                                    },
                                    // Long press is the second way into Select mode, and the one that
                                    // starts with the row you meant already picked.
                                    onLongClick = {
                                        if (!selecting) {
                                            selecting = true
                                            selection = setOf(item.id)
                                        }
                                    },
                                ),
                            leadingContent = if (selecting) {
                                { Checkbox(checked = checked, onCheckedChange = null) }
                            } else null,
                            headlineContent = { Text(item.name.ifBlank { "(unnamed)" }) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ClassifierRecipeCount(item.recipeCount)
                                    if (!selecting) {
                                        IconButton(onClick = { pendingDeletion = listOf(item) }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = "Delete ${item.name.ifBlank { "(unnamed)" }}",
                                            )
                                        }
                                    }
                                }
                            },
                            colors = if (checked) {
                                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            } else {
                                ListItemDefaults.colors()
                            },
                        )
                        HorizontalDivider(Modifier.widthIn(max = FORM_WIDTH))
                    }
                }
                EdgeScrollbar(listState)
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
    if (pendingDeletion.isNotEmpty()) {
        val rows = pendingDeletion
        AlertDialog(
            onDismissRequest = { pendingDeletion = emptyList() },
            title = { Text(classifierDeletionTitle(rows, kind)) },
            text = { Text(classifierDeletionMessage(rows, kind)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDeletion() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = emptyList() }) { Text("Cancel") } },
        )
    }
    if (mergeCandidates.isNotEmpty()) {
        ClassifierMergeDialog(
            kind = kind,
            candidates = mergeCandidates,
            survivorId = mergeSurvivorId,
            onSurvivor = { mergeSurvivorId = it },
            onConfirm = { confirmMerge() },
            onDismiss = { cancelMerge() },
        )
    }
}

/** How many recipes use one classifier — the fastest way to spot the ones worth merging or clearing out. */
@Composable
private fun ClassifierRecipeCount(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics {
            contentDescription = if (count == 1) "1 recipe" else "$count recipes"
        },
    )
}

/**
 * Confirms a merge, and is the only place the survivor is chosen — which matters because the survivor's
 * spelling is the one that remains. A dialog rather than a menu or a snackbar: the choice is a list, and
 * the outcome is irreversible.
 */
@Composable
private fun ClassifierMergeDialog(
    kind: ClassifierKind,
    candidates: List<LibraryClassifierItem>,
    survivorId: String?,
    onSurvivor: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(classifierMergeTitle(candidates, kind)) },
        text = {
            // Scrollable: merging a dozen stray tags at once is exactly what this is for, and a dialog
            // that clips its own choices would hide the row the user came to pick.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${kind.singular} to keep", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    candidates.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = item.id == survivorId,
                                    role = Role.RadioButton,
                                    onClick = { onSurvivor(item.id) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = item.id == survivorId, onClick = null)
                            Text(
                                item.name.ifBlank { "(unnamed)" },
                                Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            ClassifierRecipeCount(item.recipeCount)
                        }
                    }
                }
                Text(
                    classifierMergeMessage(candidates, survivorId, kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = survivorId != null) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/** Settings tabs, mirroring the Swift app's Settings TabView. General only exists where a general setting does. */
private enum class SettingsTab(val title: String) { General("General"), Library("Library"), Sync("Sync") }

/** Section header within a Settings tab — the counterpart of a Form section header in the Swift app. */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** Secondary caption under a control — the counterpart of the Swift app's footnote captions. */
@Composable
private fun SettingsCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The one place Salty asks for the server password — the counterpart of the Swift app's
 * SyncPasswordPrompt.
 *
 * A dialog rather than a permanent field, because a field that sits on the Settings screen forever
 * implies its contents are kept there. This is asked once, when connecting a device, and then not
 * again. The password goes to [onConnect] and is never stored: connecting trades it for a per-device
 * sync token, which is the only credential this app keeps.
 */
@Composable
private fun ConnectDevicePrompt(username: String, onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect This Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (username.isBlank()) "Enter your Salty Server password."
                    else "Enter the password for $username.",
                )
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(password) }, enabled = password.isNotEmpty()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(module: AppModule, onBack: () -> Unit) {
    // General holds only the desktop-density toggle today, so it only exists where that choice does.
    val tabs = remember {
        if (uiDensityChoiceSupported) SettingsTab.entries.toList()
        else listOf(SettingsTab.Library, SettingsTab.Sync)
    }
    var tab by remember { mutableStateOf(tabs.first()) }

    var url by remember { mutableStateOf(module.settings.serverUrl) }
    var user by remember { mutableStateOf(module.settings.username) }
    var serverUse by remember { mutableStateOf(module.settings.serverUse) }
    // Recomputed per recomposition rather than remembered: the credential can change while the screen
    // is open (connecting, forgetting, or a legacy password migrating into a token during a sync).
    val canSync = module.hasSyncCredentials
    val enrolled = module.settings.syncToken.isNotEmpty()
    // In-progress text ("Syncing…") and the long linked-folder error detail stay inline — a snackbar is
    // the wrong shape for both. Everything else is announced via [notify].
    var status by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var showResyncConfirm by remember { mutableStateOf(false) }
    var showConnectPrompt by remember { mutableStateOf(false) }
    var showForgetConfirm by remember { mutableStateOf(false) }
    var showForgetLocalOnly by remember { mutableStateOf(false) }
    var showServerHelp by remember { mutableStateOf(false) }
    var showResetLocationConfirm by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var autoSyncEnabled by remember { mutableStateOf(module.settings.autoSyncEnabled) }
    // Mirrors the switch; what the app actually themes off is [AppModule.uiDensity], written on each change.
    var touchDensity by remember { mutableStateOf(module.settings.uiDensity.isTouchFriendly) }
    val scope = rememberCoroutineScope()
    val syncProgress by module.syncProgress.collectAsState()
    // Non-null only while an ORDINARY sync is running, which is exactly when "Cancel" may be offered. The
    // force re-syncs deliberately leave it null: see the comment on the Cancel button.
    var cancellableSync by remember { mutableStateOf<Job?>(null) }
    var cancelling by remember { mutableStateOf(false) }

    /** Announce an outcome. Replaces any showing snackbar so a fast second action isn't queued behind the first. */
    fun notify(message: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }

    // Pointing at an empty folder is a supported move -- it starts a new, empty library there -- but it is
    // NOT a move of the recipes already in the old one, so say which of the two just happened. An
    // unusable folder is refused outright rather than stored and discovered at the next launch.
    val libraryPicker = rememberDirectoryPickerLauncher { dir: PlatformFile? ->
        if (dir != null) {
            when (prepareLibraryLocation(dir.path)) {
                LibraryLocationOutcome.ExistingLibrary -> {
                    module.settings.libraryPath = dir.path
                    notify("Library location set — a library is already there. Restart the app to open it.")
                }
                LibraryLocationOutcome.NewLibrary -> {
                    module.settings.libraryPath = dir.path
                    notify("Library location set — a new, empty library. Restart the app to use it.")
                }
                LibraryLocationOutcome.Unusable ->
                    notify("Salty can't create a library in that folder. The location is unchanged.")
            }
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

    fun startSync() {
        busy = true
        status = "Syncing…"
        cancelling = false
        cancellableSync = scope.launch {
            val message = try {
                "Sync complete — " + module.sync().summary()
            } catch (e: CancellationException) {
                // Swallowed on purpose, at the outermost frame of a job WE cancelled: there is
                // nothing left to unwind, and the user has to be told what Cancel left behind.
                // (notify() launches on the surrounding scope, which is still active.)
                "Sync cancelled. Changes already made were kept — the next sync resumes from here."
            } catch (e: Throwable) {
                "Sync failed: ${e.message}"
            } finally {
                busy = false
                cancellableSync = null
                cancelling = false
            }
            status = ""
            notify(message)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                    tabs.forEach { t ->
                        Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.title) })
                    }
                }
            }
        },
        // Outcomes ("Sync complete", "Folder unlinked") used to be a line of text at the bottom of a long
        // scroll — off-screen, and easy to miss entirely, right when the user wants confirmation.
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding: PaddingValues ->
        // Per-visit scroll state: switching tabs starts the newly shown tab at the top.
        val scroll = remember(tab) { ScrollState(0) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxSize().verticalScroll(scroll),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = FORM_WIDTH).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (tab) {
                        SettingsTab.General -> {
                            // Desktop only (the tab exists only there): this build defaults to compact
                            // metrics, which are wrong for the desktop machines that are actually
                            // touchscreens (a Surface, a convertible laptop).
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Touch mode", style = MaterialTheme.typography.titleSmall)
                                Switch(
                                    checked = touchDensity,
                                    onCheckedChange = {
                                        touchDensity = it
                                        module.setUiDensity(if (it) UiDensity.Comfortable else UiDensity.Compact)
                                    },
                                )
                            }
                            SettingsCaption(
                                "Restores the full-size spacing used on phones and tablets — larger rows, buttons, " +
                                    "checkboxes and text fields. Turn this on for a touchscreen; leave it off for a mouse " +
                                    "or trackpad.",
                            )
                        }

                        SettingsTab.Library -> {
                            SettingsSectionHeader("Library Location")
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (module.settings.libraryPath.isBlank()) "Current Location (Default):"
                                    else "Current Location (Custom):",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(currentLibraryDir(), style = MaterialTheme.typography.bodySmall)
                            }
                            if (customLibraryLocationSupported) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { libraryPicker.launch() }) {
                                        Text("Select Custom Library Location…")
                                    }
                                    if (module.settings.libraryPath.isNotBlank()) {
                                        TextButton(
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                            onClick = { showResetLocationConfirm = true },
                                        ) { Text("Reset to Default Location") }
                                    }
                                }
                                SettingsCaption(
                                    "Recipes and images live in a \"$SALTY_LIBRARY_DIR\" folder in the above location. " +
                                        "You'll need to restart the app for changes to take effect.",
                                )
                            } else if (linkedFolderSyncSupported) {
                                SettingsSectionHeader("Linked Folder")
                                SettingsCaption(
                                    "Salty Server is the recommended way to keep several devices in sync. As an alternative for backup " +
                                        "or one-device-at-a-time use, you can link a folder (e.g. in Nextcloud, OneDrive, or iCloud Drive) " +
                                        "that holds a copy of your library in the same \"$SALTY_LIBRARY_DIR\" format Salty for Mac opens " +
                                        "directly. The app copies your library to the folder when you leave the app and shortly after " +
                                        "edits, and loads a newer copy from the folder when it starts — it does not work live from the " +
                                        "folder, so finish on one device before opening the library on another.",
                                )
                                if (linkedLabel.isNotBlank()) {
                                    Text("Linked folder: $linkedLabel", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = !busy, onClick = { linkFolderPicker.launch() }) {
                                        Text(if (linkedLabel.isBlank()) "Link Folder…" else "Change Folder…")
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
                                        ) { Text("Sync to Folder Now") }
                                        TextButton(enabled = !busy, onClick = {
                                            module.libraryFolder.unlink()
                                            linkedLabel = ""
                                            notify("Folder unlinked. The library stays in app storage.")
                                        }) { Text("Unlink") }
                                    }
                                }
                            } else {
                                SettingsCaption(
                                    "Custom library locations are available on desktop. This device uses its app storage.",
                                )
                            }
                            // Only when idle: while busy, the Sync tab's progress block renders `status`. Its other
                            // job — the multi-line linked-folder error — is set with busy already false, so it shows.
                            if (!busy && status.isNotEmpty()) Text(status)
                        }

                        SettingsTab.Sync -> {
                            SettingsSectionHeader("Server Configuration")
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Enable sync with Salty Server", style = MaterialTheme.typography.titleSmall)
                                IconButton(onClick = { showServerHelp = true }) {
                                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "What's this?")
                                }
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = serverUse,
                                    onCheckedChange = {
                                        serverUse = it
                                        module.settings.serverUse = it
                                        // Disabling sync doesn't forget the device, so the stored token is
                                        // deliberately left alone; only a stale error message is worth clearing.
                                        if (!it) connectError = null
                                    },
                                )
                            }
                            OutlinedTextField(
                                url,
                                { url = it; module.settings.serverUrl = it },
                                label = { Text("Server URL") },
                                supportingText = { Text("Example: https://server.example.com:8443") },
                                singleLine = true,
                                enabled = serverUse,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (url.isNotEmpty() && !url.lowercase().startsWith("https")) {
                                Text(
                                    "WARNING: It is recommended to use HTTPS for better security." +
                                        if (url.startsWith("http://")) " Plain HTTP only works in debug builds on Android." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            OutlinedTextField(
                                user,
                                { user = it; module.settings.username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                // Locked while connected, like the Swift app: the token was issued for this
                                // account, so changing the name here would only misdescribe it.
                                enabled = serverUse && !canSync,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // One action, in the row a password field would otherwise occupy. Which one it is
                            // depends on whether this device is connected, so there is never a dead control
                            // here. The password itself is only ever asked for by [ConnectDevicePrompt] and
                            // never stored — connecting trades it for a per-device sync token.
                            if (canSync) {
                                TextButton(
                                    enabled = !busy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    onClick = { showForgetConfirm = true },
                                ) { Text("Forget This Device") }
                                // Named rather than described, so a user who wants to inspect the saved
                                // credential knows which OS tool to open.
                                if (enrolled) {
                                    SettingsCaption(
                                        "This device syncs with its own key, saved in ${module.settings.passwordStoreName}. " +
                                            "Your password is not stored on this device.",
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        enabled = serverUse && url.isNotBlank() && user.isNotBlank() && !connecting,
                                        onClick = { showConnectPrompt = true },
                                    ) { Text("Connect This Device") }
                                    if (connecting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                            connectError?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }

                            SettingsSectionHeader("Sync")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Sync automatically", style = MaterialTheme.typography.titleSmall)
                                Switch(
                                    checked = autoSyncEnabled,
                                    enabled = serverUse,
                                    onCheckedChange = {
                                        autoSyncEnabled = it
                                        module.settings.autoSyncEnabled = it
                                        if (!it) module.autoSync.dismissBanner() // clearing the toggle also clears any failure banner
                                    },
                                )
                            }
                            SettingsCaption(
                                "Syncs in the background after you make changes. Occasional failures are silent; " +
                                    "repeated failures show a dismissable banner.",
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    enabled = serverUse && url.isNotBlank() && !busy && canSync,
                                    onClick = { startSync() },
                                ) {
                                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (busy) "Syncing..." else "Sync Now")
                                }
                                // Offered only for an ordinary sync, which is resumable: the server's lastSyncDate is
                                // not advanced until the very end, so cancelling costs at most the work still
                                // outstanding. The two force re-syncs are one-way overwrites with no such cutoff and
                                // stay uninterruptible. Nothing is rolled back — the sync just stops making changes.
                                if (cancellableSync != null) {
                                    OutlinedButton(
                                        enabled = !cancelling,
                                        onClick = { cancelling = true; cancellableSync?.cancel() },
                                    ) { Text(if (cancelling) "Cancelling..." else "Cancel") }
                                }
                            }
                            if (busy) {
                                val p = syncProgress
                                val fraction = p?.fraction()
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(p?.describe() ?: status, style = MaterialTheme.typography.bodySmall)
                                    // Determinate only where the count is real (recipe bodies, images); the single-request
                                    // phases get the indeterminate bar rather than a made-up percentage.
                                    if (fraction != null) {
                                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }

                            OutlinedButton(
                                enabled = serverUse && url.isNotBlank() && !busy && canSync,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                onClick = { showResyncConfirm = true },
                            ) { Text("Force Full Re-Sync") }
                            SettingsCaption(
                                "Deletes everything from one side and force re-syncs from the other — either wiping the local " +
                                    "library and pulling from the server, or wiping the server and pushing from this device.",
                            )

                            // "Last synced:" re-renders when the wording is next due to change — at the 15-second
                            // mark, then on each minute boundary — rather than polling at a fixed rate. The loop
                            // lives and dies with this tab, so nothing ticks while it isn't showing.
                            val lastSyncAt = module.settings.lastSyncAt
                            var lastSyncNow by remember { mutableStateOf(LastSyncedDescription.nowMillis()) }
                            LaunchedEffect(lastSyncAt) {
                                if (lastSyncAt == 0L) return@LaunchedEffect
                                while (true) {
                                    lastSyncNow = LastSyncedDescription.nowMillis()
                                    // Half a second past the boundary, so the wake-up never lands just short of it
                                    // and immediately reschedules for a few more milliseconds.
                                    delay(LastSyncedDescription.refreshIntervalMillis(lastSyncAt, lastSyncNow) + 500)
                                }
                            }
                            SettingsCaption(
                                "Last synced: " +
                                    if (lastSyncAt == 0L) "Never" else LastSyncedDescription.text(lastSyncAt, lastSyncNow),
                            )

                            SettingsSectionHeader("How Sync Works")
                            SettingsCaption(
                                "Compares your local recipes with the server and syncs changes in both directions. " +
                                    "The most recently modified version wins in case of conflicts.",
                            )

                            // Only when idle: while busy, the progress block above already renders `status` (plus a
                            // bar), so this would double it.
                            if (!busy && status.isNotEmpty()) Text(status)
                        }
                    }
                }
            }
            EdgeScrollbar(scroll)
        }
    }

    if (showServerHelp) {
        AlertDialog(
            onDismissRequest = { showServerHelp = false },
            title = { Text("What is Salty Server?") },
            text = {
                Text(
                    "Salty Server is an optional, self-hosted sync service you can add to sync your database " +
                        "among multiple devices (as an alternative to moving or copying the database file yourself " +
                        "or relying on third-party services). For details, see: https://github.com/rmorobert/saltyserver",
                )
            },
            confirmButton = { TextButton(onClick = { showServerHelp = false }) { Text("OK") } },
        )
    }

    if (showConnectPrompt) {
        ConnectDevicePrompt(
            username = user,
            onConnect = { password ->
                showConnectPrompt = false
                connecting = true
                connectError = null
                scope.launch {
                    try {
                        module.connectDevice(password)
                        notify("This device is connected and ready to sync.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        connectError = e.message ?: "Couldn't connect this device."
                    } finally {
                        connecting = false
                    }
                }
            },
            onDismiss = { showConnectPrompt = false },
        )
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("Forget This Device?") },
            text = {
                Text(
                    "This device will stop syncing until you connect it again. Your recipes stay on this " +
                        "device and on the server.",
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showForgetConfirm = false
                        busy = true
                        scope.launch {
                            val outcome = try {
                                module.forgetDevice()
                            } finally {
                                busy = false
                            }
                            connectError = null
                            // Success is silent: the row turning back into "Connect This Device" is the
                            // confirmation. A failed revoke is worth a word, because the user is the only
                            // one who can finish the job — and only they know whether this device is merely
                            // being reset or has gone missing.
                            if (outcome == AppModule.ForgetDeviceOutcome.LOCAL_ONLY) showForgetLocalOnly = true
                        }
                    },
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { showForgetConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showForgetLocalOnly) {
        AlertDialog(
            onDismissRequest = { showForgetLocalOnly = false },
            title = { Text("Forgotten on This Device") },
            text = {
                Text(
                    "The server could not be reached to revoke your device's authorization, but local " +
                        "credentials have been deleted. You may wish to also manually remove this device from " +
                        "your active devices on your Salty Server instance if still active.",
                )
            },
            confirmButton = { TextButton(onClick = { showForgetLocalOnly = false }) { Text("OK") } },
        )
    }

    if (showResetLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showResetLocationConfirm = false },
            title = { Text("Reset Library Location") },
            text = {
                Text(
                    "This will reset your library location to the default location. You'll need to restart " +
                        "the app for changes to take effect.",
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showResetLocationConfirm = false
                        module.settings.libraryPath = ""
                        notify("Reverted to the default location. Restart the app to apply.")
                    },
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetLocationConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showResyncConfirm) {
        // Run one of the two one-way overwrites, then close the dialog and report the outcome.
        fun startResync(label: String, action: suspend () -> SyncResult) {
            showResyncConfirm = false
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
            title = { Text("Force Full Re-Sync?") },
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
