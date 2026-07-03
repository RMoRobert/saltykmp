package com.enuvro.saltykmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.enuvro.saltykmp.di.decodeImageBitmap
import com.enuvro.saltykmp.di.makeThumbnail
import com.enuvro.saltykmp.di.rememberCameraCapture
import com.enuvro.saltykmp.sync.SyncResult
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The three things recipes are organized by, each editable with identical (name) CRUD (mirrors the Swift app). */
private enum class OrganizerKind(val title: String, val singular: String) {
    Courses("Courses", "Course"),
    Categories("Categories", "Category"),
    Tags("Tags", "Tag"),
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data class ManageOrganizer(val kind: OrganizerKind) : Screen
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
fun App() {
    val module = remember { AppModule() }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
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
            StartupPhase.Ready -> AppContent(module, screen, onScreen = { screen = it })
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
    LibraryFolderSyncResult.CONFLICT -> "Both this device and the folder changed — you'll be asked which to keep on next launch."
    LibraryFolderSyncResult.ERROR -> "Couldn't access the linked folder."
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppContent(module: AppModule, screen: Screen, onScreen: (Screen) -> Unit) {
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
        BackHandler(enabled = screen != Screen.List) { onScreen(Screen.List) }
        // The active screen fills the space below the banner (each screen is its own fillMaxSize Scaffold).
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.List -> RecipeListScreen(
                    module,
                    onOpen = { onScreen(Screen.Detail(it)) },
                    onNew = { onScreen(Screen.Edit(null)) },
                    onManage = { onScreen(Screen.ManageOrganizer(it)) },
                    onSettings = { onScreen(Screen.Settings) },
                )
                is Screen.Detail -> RecipeDetailScreen(
                    module, s.id,
                    onBack = { onScreen(Screen.List) },
                    onEdit = { onScreen(Screen.Edit(s.id)) },
                )
                is Screen.Edit -> RecipeEditScreen(module, s.id, onDone = { onScreen(Screen.List) })
                is Screen.ManageOrganizer -> OrganizerEditScreen(module, s.kind, onBack = { onScreen(Screen.List) })
                Screen.Settings -> SettingsScreen(module, onBack = { onScreen(Screen.List) })
            }
        }
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
    }
    val sorted = list.sortedWith(key.thenBy { it.name.lowercase() })
    return if (ascending) sorted else sorted.reversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeListScreen(
    module: AppModule,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onManage: (OrganizerKind) -> Unit,
    onSettings: () -> Unit,
) {
    var filter by remember { mutableStateOf<RecipeFilter>(RecipeFilter.All) }
    var sort by remember {
        mutableStateOf(runCatching { RecipeSort.valueOf(module.settings.recipeSort) }.getOrDefault(RecipeSort.NAME))
    }
    var ascending by remember { mutableStateOf(module.settings.recipeSortAscending) }
    var sortMenu by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val recipes by remember(filter) {
        when (val f = filter) {
            RecipeFilter.All -> module.repository.recipes()
            is RecipeFilter.Course -> module.repository.recipesForCourse(f.id)
            is RecipeFilter.Category -> module.repository.recipesForCategory(f.id)
            is RecipeFilter.Tag -> module.repository.recipesForTag(f.id)
        }
    }.collectAsState(initial = emptyList())
    val sorted = remember(recipes, sort, ascending) { sortRecipes(recipes, sort, ascending) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            RecipeDrawer(
                module,
                selected = filter,
                onSelect = { picked ->
                    filter = picked
                    scope.launch { drawerState.close() }
                },
                onManage = { kind ->
                    scope.launch { drawerState.close() }
                    onManage(kind)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(filter.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = {
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
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = "New recipe")
                }
            },
        ) { padding ->
            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        if (filter is RecipeFilter.All) "No recipes yet — open Settings to sync, or tap New."
                        else "No recipes in \"${filter.title}\".",
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(sorted) { recipe ->
                        // Prefer the cached thumbnail blob; fall back to the full image for rows synced
                        // before thumbnail caching (a re-sync backfills the blob).
                        val thumb = remember(recipe.imageThumbnailData, recipe.imageFilename) {
                            (recipe.imageThumbnailData
                                ?: recipe.imageFilename?.let { module.imageFiles.load(it) })
                                ?.let { decodeImageBitmap(it) }
                        }
                        ListItem(
                            leadingContent = { RecipeThumbnail(thumb) },
                            headlineContent = { Text(if (recipe.isFavorite == true) "★ ${recipe.name}" else recipe.name) },
                            supportingContent = recipe.source?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            modifier = Modifier.clickable { onOpen(recipe.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDrawer(
    module: AppModule,
    selected: RecipeFilter,
    onSelect: (RecipeFilter) -> Unit,
    onManage: (OrganizerKind) -> Unit,
) {
    val courses by module.repository.courses().collectAsState(initial = emptyList())
    val categories by module.repository.categories().collectAsState(initial = emptyList())
    val tags by module.repository.tags().collectAsState(initial = emptyList())

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Salty",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            NavigationDrawerItem(
                label = { Text("All Recipes") },
                selected = selected is RecipeFilter.All,
                onClick = { onSelect(RecipeFilter.All) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            if (categories.isNotEmpty()) {
                DrawerSectionHeader("Categories")
                categories.forEach { c ->
                    val name = c.name ?: "(unnamed)"
                    NavigationDrawerItem(
                        label = { Text(name) },
                        selected = (selected as? RecipeFilter.Category)?.id == c.id,
                        onClick = { onSelect(RecipeFilter.Category(c.id, name)) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            if (courses.isNotEmpty()) {
                DrawerSectionHeader("Courses")
                courses.forEach { c ->
                    val name = c.name ?: "(unnamed)"
                    NavigationDrawerItem(
                        label = { Text(name) },
                        selected = (selected as? RecipeFilter.Course)?.id == c.id,
                        onClick = { onSelect(RecipeFilter.Course(c.id, name)) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            if (tags.isNotEmpty()) {
                DrawerSectionHeader("Tags")
                tags.forEach { t ->
                    val name = t.name ?: "(unnamed)"
                    NavigationDrawerItem(
                        label = { Text(name) },
                        selected = (selected as? RecipeFilter.Tag)?.id == t.id,
                        onClick = { onSelect(RecipeFilter.Tag(t.id, name)) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerSectionHeader("Manage Library")
            OrganizerKind.entries.forEach { kind ->
                NavigationDrawerItem(
                    label = { Text("Edit ${kind.title}") },
                    selected = false,
                    onClick = { onManage(kind) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
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
private fun RecipeDetailScreen(module: AppModule, id: String, onBack: () -> Unit, onEdit: () -> Unit) {
    // recipeForUpload gives the full ServerRecipe incl. category/tag ids (the db row omits junctions).
    val recipe = remember(id) { module.localStore.recipeForUpload(id) }
    val image = remember(recipe?.imageFilename) {
        recipe?.imageFilename?.let { fn -> module.imageFiles.load(fn)?.let { decodeImageBitmap(it) } }
    }
    val courseName = remember(id) {
        recipe?.courseId?.let { cid -> module.localStore.courses().firstOrNull { it.id == cid }?.name?.takeIf { it.isNotBlank() } }
    }
    val categoryNames = remember(id) {
        val m = module.localStore.categories().associate { it.id to it.name }
        recipe?.categoryIds.orEmpty().mapNotNull { m[it]?.takeIf { n -> n.isNotBlank() } }
    }
    val tagNames = remember(id) {
        val m = module.localStore.tags().associate { it.id to it.name }
        recipe?.tagIds.orEmpty().mapNotNull { m[it]?.takeIf { n -> n.isNotBlank() } }
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.name ?: "Recipe") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = recipe.name,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
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
            }
            val flags = buildList {
                if (recipe.isFavorite == true) add("★ Favorite")
                if (recipe.wantToMake == true) add("Want to Make")
            }
            if (metaItems.isNotEmpty() || flags.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        metaItems.forEach { (k, v) -> DetailMeta(k, v) }
                        if (flags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                flags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
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
            if (categoryNames.isNotEmpty()) DetailSection("Categories") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categoryNames.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            if (tagNames.isNotEmpty()) DetailSection("Tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tagNames.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete recipe?") },
            text = { Text("\"${recipe?.name.orEmpty()}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    module.localStore.deleteRecipe(id)
                    module.autoSync.notifyChange()
                    showDeleteConfirm = false
                    onBack()
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecipeEditScreen(module: AppModule, id: String?, onDone: () -> Unit) {
    val existing = remember(id) { id?.let { module.localStore.recipeForUpload(it) } }
    // The DB row carries the cached thumbnail blob, which we must preserve across edits.
    val existingRow = remember(id) { id?.let { module.repository.recipe(it) } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
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
    var pickedImage by remember(id) { mutableStateOf<ByteArray?>(null) }
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
        module.autoSync.notifyChange()
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New Recipe" else "Edit Recipe") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    IconButton(onClick = save, enabled = name.isNotBlank()) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(intro, { intro = it }, label = { Text("Introduction") }, modifier = Modifier.fillMaxWidth())

            Text("Image", style = MaterialTheme.typography.titleMedium)
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favorite")
                Spacer(Modifier.width(12.dp))
                Switch(checked = favorite, onCheckedChange = { favorite = it })
                Spacer(Modifier.width(24.dp))
                Text("Want to Make")
                Spacer(Modifier.width(12.dp))
                Switch(checked = wantToMake, onCheckedChange = { wantToMake = it })
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
            PickerField(
                label = "Rating",
                options = listOf<Pair<String, Int?>>(
                    "Not set" to null, "★" to 1, "★★" to 2, "★★★" to 3, "★★★★" to 4, "★★★★★" to 5,
                ),
                selected = rating,
                onSelect = { rating = it },
            )
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
                MultiSelectChips("Categories", categories.map { (it.name ?: "(unnamed)") to it.id }, selectedCategories)
            }
            if (tags.isNotEmpty()) {
                MultiSelectChips("Tags", tags.map { (it.name ?: "(unnamed)") to it.id }, selectedTags)
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

// Editable ingredient/direction rows. A "heading" row is a section title (not bulleted/numbered when
// displayed). Headings are created via the "Add heading" button; there's no per-row toggle (keeps the UI
// clean). The flags are still carried on each row so existing headings — and the ingredient "main" flag
// set in the SwiftUI app, which has no CMP UI yet — round-trip through a save untouched.
private data class IngredientRow(val id: String, val text: String = "", val isHeading: Boolean = false, val isMain: Boolean = false)
private data class DirectionRow(val id: String, val text: String = "", val isHeading: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientEditList(items: SnapshotStateList<IngredientRow>) {
    Text("Ingredients", style = MaterialTheme.typography.titleMedium)
    items.forEachIndexed { i, item ->
        SectionRow(
            text = item.text,
            isHeading = item.isHeading,
            placeholder = if (item.isHeading) "Section heading" else "Ingredient",
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
    Text("Directions", style = MaterialTheme.typography.titleMedium)
    items.forEachIndexed { i, item ->
        SectionRow(
            text = item.text,
            isHeading = item.isHeading,
            placeholder = if (item.isHeading) "Section heading" else "Step",
            onTextChange = { items[i] = item.copy(text = it) },
            onRemove = { items.removeAt(i) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { items.add(DirectionRow(newId())) }) { Text("+ Add step") }
        TextButton(onClick = { items.add(DirectionRow(newId(), isHeading = true)) }) { Text("+ Add heading") }
    }
}

/** A single ingredient/direction text field + remove button; heading rows render bold so they read as
 * section titles even though there's no per-row toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionRow(
    text: String,
    isHeading: Boolean,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(placeholder) },
            textStyle = if (isHeading) LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold) else LocalTextStyle.current,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
    }
}

/** A labeled single-choice dropdown; [options] are (display, value) pairs and value may be null ("none"). */
@Composable
private fun <T> PickerField(label: String, options: List<Pair<String, T?>>, selected: T?, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first ?: "—"
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $current") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/** Toggleable chips for multi-selecting ids; mutates [selected] in place. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectChips(title: String, options: List<Pair<String, String>>, selected: SnapshotStateList<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val isSel = selected.contains(value)
            FilterChip(
                selected = isSel,
                onClick = { if (isSel) selected.remove(value) else selected.add(value) },
                label = { Text(label) },
            )
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
    Text(title, style = MaterialTheme.typography.titleMedium)
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
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Nutrition", style = MaterialTheme.typography.titleMedium)
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

private data class OrganizerItem(val id: String, val name: String)

/**
 * Add / rename / delete one library vocabulary (courses, categories, or tags) — the KMP equivalent of
 * the Swift app's LibraryCoursesEditView / LibraryCategoryEditView / LibraryTagsEditView. Edits are
 * written locally with a fresh lastModifiedDate; the next sync pushes them to the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizerEditScreen(module: AppModule, kind: OrganizerKind, onBack: () -> Unit) {
    val items by remember(kind) {
        when (kind) {
            OrganizerKind.Courses -> module.repository.courses().map { l -> l.map { OrganizerItem(it.id, it.name ?: "") } }
            OrganizerKind.Categories -> module.repository.categories().map { l -> l.map { OrganizerItem(it.id, it.name ?: "") } }
            OrganizerKind.Tags -> module.repository.tags().map { l -> l.map { OrganizerItem(it.id, it.name ?: "") } }
        }
    }.collectAsState(initial = emptyList())

    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<OrganizerItem?>(null) }

    fun save(id: String, name: String) {
        val ts = nowTimestamp()
        when (kind) {
            OrganizerKind.Courses -> module.localStore.upsertCourse(ServerCourse(id, name, ts))
            OrganizerKind.Categories -> module.localStore.upsertCategory(ServerCategory(id, name, ts))
            OrganizerKind.Tags -> module.localStore.upsertTag(ServerTag(id, name, ts))
        }
        module.autoSync.notifyChange()
    }

    fun delete(id: String) {
        when (kind) {
            OrganizerKind.Courses -> module.localStore.deleteCourse(id)
            OrganizerKind.Categories -> module.localStore.deleteCategory(id)
            OrganizerKind.Tags -> module.localStore.deleteTag(id)
        }
        module.autoSync.notifyChange()
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
                Text("No ${kind.title.lowercase()} yet — tap New to add one.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name.ifBlank { "(unnamed)" }) },
                        trailingContent = {
                            IconButton(onClick = { delete(item.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier.clickable { renaming = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        OrganizerNameDialog(
            title = "New ${kind.singular}",
            initial = "",
            confirmLabel = "Add",
            onConfirm = { name -> save(newId(), name); adding = false },
            onDismiss = { adding = false },
        )
    }
    renaming?.let { target ->
        OrganizerNameDialog(
            title = "Rename ${kind.singular}",
            initial = target.name,
            confirmLabel = "Save",
            onConfirm = { name -> save(target.id, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun OrganizerNameDialog(
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
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showResyncConfirm by remember { mutableStateOf(false) }
    var autoSyncEnabled by remember { mutableStateOf(module.settings.autoSyncEnabled) }
    val scope = rememberCoroutineScope()
    val libraryPicker = rememberDirectoryPickerLauncher { dir: PlatformFile? ->
        if (dir != null) {
            module.settings.libraryPath = dir.path
            status = "Library location set. Restart the app to use the new location."
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
                status = if (result == LibraryFolderSyncResult.ERROR && detail != null) {
                    "${folderSyncMessage(result)}\n$detail"
                } else {
                    folderSyncMessage(result)
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
    ) { padding: PaddingValues ->
        Column(
            Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
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
            Button(
                enabled = !busy,
                onClick = {
                    module.settings.serverUrl = url
                    module.settings.username = user
                    module.settings.password = pass
                    busy = true
                    status = "Syncing…"
                    scope.launch {
                        status = try {
                            "Sync complete — " + module.sync().summary()
                        } catch (e: Throwable) {
                            "Sync failed: ${e.message}"
                        } finally {
                            busy = false
                        }
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
                            status = "Reverted to the default location. Restart the app to apply."
                        }) { Text("Use default") }
                    }
                }
                Text(
                    "Recipes and images live in a \"$SALTY_LIBRARY_DIR\" folder in the above location. Must restart app after changing to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (linkedFolderSyncSupported) {
                Text(
                    "Link a folder (e.g. inside a personal cloud storage service) to keep a synced copy of your library " +
                        "there. The app copies your library to and from the folder on open and close or on demand (it does " +
                        "not work live from the folder). Salty Server is recommended if using multiple devices, otherwise " +
                        "exercise caution to not open database on new device before syncing when finished on existing device.",
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
                                    status = folderSyncMessage(r)
                                    busy = false
                                }
                            },
                        ) { Text("Sync to folder now") }
                        TextButton(enabled = !busy, onClick = {
                            module.libraryFolder.unlink()
                            linkedLabel = ""
                            status = "Folder unlinked. The library stays in app storage."
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
                status = try {
                    "$label complete — " + action().summary()
                } catch (e: Throwable) {
                    "$label failed: ${e.message}"
                } finally {
                    busy = false
                }
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
