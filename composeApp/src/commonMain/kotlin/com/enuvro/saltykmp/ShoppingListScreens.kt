package com.enuvro.saltykmp

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.enuvro.saltykmp.db.ShoppingList
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.shopping.ShoppingListText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shopping lists, mirroring the Swift app's two kinds: a **checklist** of tappable items with optional
 * heading rows, and a **freeform** Markdown-ish text list. Both sync through the same revision-based
 * path as the Swift client (see SHOPPING_LIST_REVISIONS_PLAN.md); every edit here goes through
 * [ShoppingListStore], which preserves the sync baseline.
 */

/** Quiet period before a typed edit is written to the DB. Long enough to coalesce a burst of keystrokes,
 *  short enough that backgrounding the app right after typing still catches it. */
private const val TEXT_WRITE_DEBOUNCE_MS = 400L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListsScreen(
    module: AppModule,
    onOpen: (String) -> Unit,
    /** App-bar back button; null where the navigation is already on screen beside this pane. */
    onBack: (() -> Unit)?,
    /** The list showing in the detail pane, highlighted here. Only set in a two-pane layout. */
    selectedId: String? = null,
    /** Bumped by the desktop Find command; opens the search field whenever it changes. */
    findRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    // null until the query first emits, so the "create your first list" copy never flashes over real lists.
    val loaded by module.shoppingLists.lists().collectAsState(initial = null)
    val lists = loaded.orEmpty()
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var newListFreeform by remember { mutableStateOf<Boolean?>(null) }
    var renaming by remember { mutableStateOf<ShoppingList?>(null) }
    var deleting by remember { mutableStateOf<ShoppingList?>(null) }
    var addMenu by remember { mutableStateOf(false) }

    // Skipping 0 keeps the field shut on first composition.
    LaunchedEffect(findRequest) {
        if (findRequest > 0) searchActive = true
    }

    // Searching a list matches its name OR its contents, so "paprika" finds the list that contains it.
    val shown = remember(lists, query) {
        val q = query.trim()
        if (q.isEmpty()) lists else lists.filter { l ->
            l.name.orEmpty().contains(q, ignoreCase = true) ||
                l.contentsForFreeform.orEmpty().contains(q, ignoreCase = true) ||
                l.contentsForList.orEmpty().any { it.text.contains(q, ignoreCase = true) }
        }
    }

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
                            placeholder = { Text("Search shopping lists") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
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
                        Text("Shopping Lists")
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = { searchActive = false; query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "New shopping list")
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New Checklist") },
                        onClick = { addMenu = false; newListFreeform = false },
                    )
                    DropdownMenuItem(
                        text = { Text("New Freeform List") },
                        onClick = { addMenu = false; newListFreeform = true },
                    )
                }
            }
        },
    ) { padding ->
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {} // pre-first-emission: no empty-state flash
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (query.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matches",
                        body = "No list's name or contents matches \"${query.trim()}\".",
                    )
                } else {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.ListAlt,
                        title = "No shopping lists",
                        body = "A checklist gives you tappable items and headings. A freeform list is a " +
                            "plain Markdown text box.",
                        actionLabel = "New checklist",
                        onAction = { newListFreeform = false },
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(shown, key = { it.id }) { list ->
                        var rowMenu by remember(list.id) { mutableStateOf(false) }
                        Box {
                            ListItem(
                                headlineContent = { Text(list.name.orEmpty().ifBlank { "(untitled)" }) },
                                colors = if (list.id == selectedId) {
                                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    ListItemDefaults.colors()
                                },
                                supportingContent = {
                                    Text(
                                        ShoppingListText.contentsSummary(
                                            isFreeform = list.isFreeform == true,
                                            items = list.contentsForList.orEmpty(),
                                            freeformText = list.contentsForFreeform,
                                        ),
                                    )
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = { onOpen(list.id) },
                                    onLongClick = { rowMenu = true },
                                ),
                            )
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename…") },
                                    onClick = { rowMenu = false; renaming = list },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete…") },
                                    onClick = { rowMenu = false; deleting = list },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                EdgeScrollbar(listState)
            }
        }
    }

    newListFreeform?.let { isFreeform ->
        ShoppingListNameDialog(
            title = if (isFreeform) "New Freeform List" else "New Checklist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { newListFreeform = null },
            onConfirm = { name ->
                val id = module.shoppingLists.create(name, isFreeform)
                module.onLocalChange()
                newListFreeform = null
                onOpen(id)
            },
        )
    }

    renaming?.let { list ->
        ShoppingListNameDialog(
            title = "Rename List",
            initial = list.name.orEmpty(),
            confirmLabel = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                module.shoppingLists.rename(list, name)
                module.onLocalChange()
                renaming = null
            },
        )
    }

    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete list?") },
            text = { Text("\"${list.name.orEmpty().ifBlank { "(untitled)" }}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    module.shoppingLists.delete(list.id)
                    module.onLocalChange()
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Shared name prompt for creating and renaming lists; the confirm button is disabled while blank. */
@Composable
private fun ShoppingListNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * One shopping list. Checklists edit in place (tap the circle to complete, type in the row); freeform
 * lists are a plain Markdown text box — deliberately code-only, with no rendered preview, matching what
 * the freeform kind is for.
 *
 * **Local Compose state is the single source of truth while the screen is open.** It is seeded once from
 * the DB and never read back: a field driven by the DB query drops and reorders characters, because each
 * keystroke writes, the query re-emits asynchronously, and a stale value lands back in the field a frame
 * later. (Adopting only "changes we didn't write" doesn't fix it either — an echo of keystroke N-1 still
 * arrives after keystroke N and no longer matches.) The cost is that a sync landing while a list is open
 * won't show until it is reopened, which is the same trade the Swift app's view model makes.
 *
 * Text edits persist on a short debounce and structural edits (add/delete/toggle/clear) persist at once;
 * whatever is still pending is flushed when the screen leaves, so nothing is lost without a save button.
 * Each write is what marks the row dirty for the next sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListDetailScreen(
    module: AppModule,
    id: String,
    /** App-bar back button; null in a split view, where the lists never left the screen. */
    onBack: (() -> Unit)?,
    /** Leave this list — used when it disappears out from under us (deleted here or by a sync). */
    onClose: () -> Unit,
    onUndoable: (message: String, undo: () -> Unit) -> Unit,
) {
    // `null` = the query hasn't emitted yet, which is NOT the same as "no such list": treating the initial
    // empty emission as a missing list would bounce straight back out of a list opened right after creating it.
    val lists by module.shoppingLists.lists().collectAsState(initial = null)
    val list = lists?.firstOrNull { it.id == id }
    var hideCompleted by remember(id) { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmConvert by remember { mutableStateOf(false) }
    var focusItemId by remember(id) { mutableStateOf<String?>(null) }

    // Local edit state, seeded once per list (see the doc comment) — never re-read from the DB.
    val items = remember(id) { mutableStateListOf<ShoppingListListContents>() }
    var freeformText by remember(id) { mutableStateOf("") }
    var seeded by remember(id) { mutableStateOf(false) }
    // A pending debounced text write; also flushed on leaving the screen.
    val scope = rememberCoroutineScope()
    var pendingWrite by remember(id) { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    if (list != null && !seeded) {
        items.clear()
        items.addAll(list.contentsForList.orEmpty())
        freeformText = list.contentsForFreeform.orEmpty()
        seeded = true
    }

    // Flush a debounced edit that the user walked away from mid-typing. Re-reads the row rather than
    // capturing it, so the write always lands on the current version.
    DisposableEffect(id) {
        onDispose {
            pendingWrite?.let { job ->
                job.cancel()
                module.shoppingLists.list(id)?.let { row ->
                    if (row.isFreeform == true) module.shoppingLists.setFreeformText(row, freeformText)
                    else module.shoppingLists.setItems(row, items.toList())
                    module.onLocalChange()
                }
            }
        }
    }

    if (lists == null) return // first frame only
    if (list == null) {
        // The list was deleted (here or by a sync) while it was open.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val isFreeform = list.isFreeform == true

    /** Write the current local items straight through (structural edits: add, delete, toggle, clear). */
    fun persistItems() {
        pendingWrite?.cancel()
        pendingWrite = null
        module.shoppingLists.setItems(list, items.toList())
        module.onLocalChange()
    }

    /**
     * Persist after a quiet period. Typing writes on every keystroke otherwise, and each write re-runs the
     * query that feeds this screen — enough main-thread churn to garble the field being typed into.
     */
    fun schedulePersist(write: () -> Unit) {
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(TEXT_WRITE_DEBOUNCE_MS)
            write()
            module.onLocalChange()
            pendingWrite = null
        }
    }

    fun replaceItems(next: List<ShoppingListListContents>) {
        items.clear()
        items.addAll(next)
        persistItems()
    }

    /**
     * Insert a row after [afterId] (or at the end), first dropping any trailing blank draft so repeated
     * taps of + don't stack empty rows — the same "abandoned draft" rule the Swift app applies.
     */
    fun addRow(isHeading: Boolean, afterId: String? = null) {
        val row = ShoppingListListContents(
            id = newId(),
            isCompleted = if (isHeading) null else false,
            isHeading = isHeading,
            text = "",
        )
        val base = items.filterNot { it.id != afterId && it.text.isBlank() }
        val at = base.indexOfFirst { it.id == afterId }
        replaceItems(if (at >= 0) base.toMutableList().apply { add(at + 1, row) } else base + row)
        focusItemId = row.id
        // Bring the new row into view: appended to a list taller than the screen it would otherwise be
        // added off-screen, and the tap would look like it did nothing (focus can't land on a row that
        // LazyColumn hasn't composed yet either).
        val index = items.indexOfFirst { it.id == row.id }
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list.name.orEmpty().ifBlank { "(untitled)" }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "List options")
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            if (isFreeform) {
                                DropdownMenuItem(
                                    text = { Text("Convert to Checklist") },
                                    onClick = { overflow = false; confirmConvert = true },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("New Heading") },
                                    onClick = { overflow = false; addRow(isHeading = true) },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (hideCompleted) "Show Completed" else "Hide Completed") },
                                    onClick = { overflow = false; hideCompleted = !hideCompleted },
                                )
                                DropdownMenuItem(
                                    text = { Text("Uncheck All Items") },
                                    onClick = {
                                        overflow = false
                                        replaceItems(items.map { if (it.isHeading == true) it else it.copy(isCompleted = false) })
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Completed") },
                                    onClick = {
                                        overflow = false
                                        replaceItems(items.filter { it.isHeading == true || it.isCompleted != true })
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Convert to Freeform Text") },
                                    onClick = { overflow = false; confirmConvert = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!isFreeform) {
                FloatingActionButton(onClick = { addRow(isHeading = false) }, modifier = Modifier.imePadding()) {
                    Icon(Icons.Outlined.Add, contentDescription = "New item")
                }
            }
        },
    ) { padding ->
        if (isFreeform) {
            OutlinedTextField(
                value = freeformText.orEmpty(),
                onValueChange = { text ->
                    freeformText = text
                    schedulePersist { module.shoppingLists.setFreeformText(list, text) }
                },
                // Monospaced: this is the raw Markdown, not a rendered document.
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("# Produce\n* [ ] Apples\n* [x] Bananas") },
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp).imePadding(),
            )
        } else {
            val visible = if (hideCompleted) items.filter { it.isCompleted != true } else items.toList()
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (items.isEmpty()) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.ListAlt,
                            title = "No items yet",
                            body = "Add items one at a time — press Return to start the next one. Swipe an " +
                                "item away to delete it.",
                            actionLabel = "Add item",
                            onAction = { addRow(isHeading = false) },
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.CheckCircle,
                            title = "All done",
                            body = "Every item is completed. Show them again from the menu, or clear them out.",
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(visible, key = { it.id }) { item ->
                            ShoppingListItemRow(
                                item = item,
                                requestFocus = focusItemId == item.id,
                                onFocused = { focusItemId = null },
                                onToggle = {
                                    val i = items.indexOfFirst { it.id == item.id }
                                    if (i >= 0) {
                                        items[i] = items[i].copy(isCompleted = items[i].isCompleted != true)
                                        persistItems()
                                    }
                                },
                                onText = { text ->
                                    val i = items.indexOfFirst { it.id == item.id }
                                    if (i >= 0) {
                                        items[i] = items[i].copy(text = text)
                                        schedulePersist { module.shoppingLists.setItems(list, items.toList()) }
                                    }
                                },
                                onSubmit = { addRow(isHeading = item.isHeading == true, afterId = item.id) },
                                onDelete = {
                                    val before = items.toList()
                                    replaceItems(items.filterNot { it.id == item.id })
                                    val label = item.text.ifBlank { "item" }
                                    onUndoable("Deleted \"$label\"") { replaceItems(before) }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    EdgeScrollbar(listState)
                }
            }
        }
    }

    if (confirmConvert) {
        AlertDialog(
            onDismissRequest = { confirmConvert = false },
            title = { Text(if (isFreeform) "Convert to checklist?" else "Convert to freeform text?") },
            text = {
                Text(
                    if (isFreeform) {
                        "Each line becomes a checklist item; \"#\" lines become headings. The text is kept, " +
                            "so you can convert back."
                    } else {
                        "The items become Markdown-style text lines. The items are kept, so you can convert back."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Convert from the row as the DB has it, then let both sides re-seed from the result.
                    // Convert from the DB row, then re-seed local state from the result.
                    if (isFreeform) module.shoppingLists.convertToChecklist(list)
                    else module.shoppingLists.convertToFreeform(list)
                    module.onLocalChange()
                    seeded = false
                    confirmConvert = false
                }) { Text("Convert") }
            },
            dismissButton = { TextButton(onClick = { confirmConvert = false }) { Text("Cancel") } },
        )
    }
}

/**
 * One checklist row, swipeable in either direction to delete.
 *
 * The text field carries no container or indicator line: with one per row, the default filled styling turns
 * a shopping list into a stack of form inputs. Deletion is a swipe rather than a per-row trash button for
 * the same reason — it's the Material list gesture, and it removes a third piece of chrome from every row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingListItemRow(
    item: ShoppingListListContents,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onToggle: () -> Unit,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Deleting a shopping item is cheap to redo (and the alternative was a button on every row), so a
    // completed swipe commits rather than asking — with an Undo snackbar as the way back.
    //
    // Two things here are load-bearing, both learned the hard way:
    //  - `confirmValueChange` returns false, so the box never SETTLES into a dismissed state. Letting it
    //    settle means an undone row comes back still holding a dismissed swipe state (LazyColumn keeps
    //    per-key state), which immediately re-deletes it — undo appeared to do nothing.
    //  - the delete fires at most once per row, because the callback can be invoked more than once for a
    //    single swipe. Without the guard, the second call captures an already-deleted list as its undo
    //    baseline, so Undo restores nothing.
    val currentOnDelete by rememberUpdatedState(onDelete)
    var deleteRequested by remember(item.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !deleteRequested) {
                deleteRequested = true
                currentOnDelete()
            }
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        ShoppingListItemRowContent(item, requestFocus, onFocused, onToggle, onText, onSubmit, onDelete)
    }
}

@Composable
private fun ShoppingListItemRowContent(
    item: ShoppingListListContents,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onToggle: () -> Unit,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Return inserts the next row and moves focus there, so a run of items can be typed without
    // reaching for a button — which also matters because the keyboard covers the FAB while typing.
    val imeOptions = KeyboardOptions(imeAction = ImeAction.Next)
    val imeActions = KeyboardActions(onNext = { onSubmit() })
    val isHeading = item.isHeading == true
    val isCompleted = item.isCompleted == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focus.requestFocus()
            onFocused()
        }
    }

    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isHeading) {
            // Align heading text with item text, which sits after the completion toggle.
            Spacer(Modifier.width(48.dp))
            TextField(
                value = item.text,
                onValueChange = onText,
                placeholder = { Text("Heading") },
                singleLine = true,
                keyboardOptions = imeOptions,
                keyboardActions = imeActions,
                textStyle = MaterialTheme.typography.titleMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        } else {
            IconButton(onClick = onToggle) {
                Icon(
                    if (isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (isCompleted) "Completed" else "Not completed",
                    tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            TextField(
                value = item.text,
                onValueChange = onText,
                placeholder = { Text("Item") },
                singleLine = true,
                keyboardOptions = imeOptions,
                keyboardActions = imeActions,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
    }
}
