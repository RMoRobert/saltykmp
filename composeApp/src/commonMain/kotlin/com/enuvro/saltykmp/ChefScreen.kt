package com.enuvro.saltykmp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enuvro.saltykmp.db.model.Ingredient

/**
 * Chef Mode: the recipe alone, big, and awake — a port of the Swift app's Chef View.
 *
 * This is the one screen with no app around it. It replaces the whole window rather than sitting inside
 * the shell (see the branch in [App]), so the sidebar, the rail, the recipe list and the detail bar all
 * go and the recipe gets the entire display. What is left is what you are standing in the kitchen
 * asking: what goes in, and what to do next.
 *
 * Three things it is built around, none of which the recipe detail screen has to care about:
 *
 * - **Legible from a step or two back.** Type starts well above the reading size and steps up to 3x
 *   (see [ChefTextSize]). Completed rows keep full contrast — a dimmed step is exactly the one being
 *   squinted at from across the room, so completion is said with a checkmark instead.
 * - **Usable with a floury knuckle *and* a mouse.** Every control is a full touch target, the compact
 *   desktop density is deliberately undone below, One Step mode makes the outer thirds of the screen
 *   into previous/next, and the keyboard drives the whole thing (arrows, Escape, +/-).
 * - **One obvious way out.** "Exit" is the first thing in the header, inset from the corner rather than
 *   flush against it — a small control jammed into a screen edge is the hardest thing to hit on a
 *   touchscreen, and this is the control that must never be missed.
 *
 * Cooking progress lives in [AppModule.chefSessions] for the app session, so ducking out to look
 * something up and coming back returns to the same checked ingredients and the same current step. The
 * text size and display style are ordinary persisted preferences; the progress deliberately is not.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChefScreen(module: AppModule, id: String, onExit: () -> Unit) {
    val recipe = remember(id) { module.localStore.recipeForUpload(id) }
    val steps = remember(recipe) { chefSteps(recipe?.directions) }
    val ingredients = remember(recipe) { recipe?.ingredients.orEmpty() }

    var session by remember(id) { mutableStateOf(module.chefSessions.state(id)) }
    fun updateSession(next: ChefSessionState) {
        session = next
        module.chefSessions.save(id, next)
    }

    var textLevel by remember { mutableStateOf(ChefTextSize.clamped(module.settings.chefTextSizeLevel)) }
    fun setTextLevel(level: Int) {
        textLevel = ChefTextSize.clamped(level)
        module.settings.chefTextSizeLevel = textLevel
    }

    var style by remember { mutableStateOf(module.settings.chefDisplayStyle) }
    fun setStyle(next: ChefDisplayStyle) {
        style = next
        module.settings.chefDisplayStyle = next
    }

    /** Forget this recipe's cooking progress; the store drops a pristine session on its own. */
    fun startOver() {
        module.chefSessions.reset(id)
        session = ChefSessionState()
    }

    var showIngredientsSheet by remember { mutableStateOf(false) }
    var confirmPrepared by remember { mutableStateOf(false) }
    var didMarkPrepared by remember(id) { mutableStateOf(false) }

    val scale = ChefTextSize.scale(textLevel)
    val progress = ChefProgress(steps, session)

    KeepScreenAwake()
    // The way out, for the platforms that have a system back. The header's Exit button is the one that
    // is always visible, and Escape does the same on a keyboard.
    BackHandler(enabled = true) { onExit() }

    val focus = remember { FocusRequester() }
    LaunchedEffect(id) { focus.requestFocus() }

    val outerDensity = LocalDensity.current
    // Chef Mode is touch-first on every platform, including the desktop one that opted into compact
    // metrics. Dividing by the same factor SaltyTheme multiplied by restores full-size controls here
    // and nowhere else; type is unaffected either way (see DensityMetrics.layoutScale).
    val layoutScale = LocalUiDensity.current.metrics.layoutScale
    CompositionLocalProvider(
        LocalDensity provides outerDensity.scaledBy(1f / layoutScale),
        LocalMinimumInteractiveComponentSize provides 48.dp,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    // Preview, so the keys work wherever focus has landed among the buttons below.
                    // Space and Enter are deliberately absent: they activate whichever control is
                    // focused, and stealing them would break checking off a step with the keyboard.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onExit(); true }
                            Key.DirectionRight, Key.PageDown ->
                                if (progress.canAdvance) { updateSession(progress.next()); true } else false
                            Key.DirectionLeft, Key.PageUp ->
                                if (progress.canGoToPreviousStep) { updateSession(progress.previous()); true } else false
                            Key.Equals, Key.Plus, Key.NumPadAdd -> { setTextLevel(textLevel + 1); true }
                            Key.Minus, Key.NumPadSubtract -> { setTextLevel(textLevel - 1); true }
                            // Up/Down are left alone so they still scroll the list.
                            else -> false
                        }
                    },
            ) {
                // Side by side only where the directions still get a usable measure afterwards. Below
                // this the ingredients move behind a button, because mid-cook the directions are what
                // needs the screen — the same trade the Swift app makes at compact width.
                val ingredientsPane = maxWidth >= CHEF_PANE_MIN_WIDTH && ingredients.isNotEmpty() && steps.isNotEmpty()
                val paneWidth = chefIngredientsPaneWidthDp(maxWidth.value, scale).dp
                val roomForName = maxWidth >= CHEF_HEADER_NAME_MIN_WIDTH

                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ChefHeaderBar(
                        recipeName = recipe?.name.orEmpty(),
                        showName = roomForName,
                        showStyleToggle = roomForName && steps.isNotEmpty(),
                        // No point offering a way to present directions a recipe hasn't got.
                        offerDisplayStyle = steps.isNotEmpty(),
                        showIngredientsButton = !ingredientsPane && ingredients.isNotEmpty() && steps.isNotEmpty(),
                        style = style,
                        textLevel = textLevel,
                        hasProgress = progress.hasProgress,
                        didMarkPrepared = didMarkPrepared,
                        canMarkPrepared = recipe != null,
                        onExit = onExit,
                        onIngredients = { showIngredientsSheet = true },
                        onStyle = ::setStyle,
                        onTextLevel = ::setTextLevel,
                        onStartOver = ::startOver,
                        onMarkPrepared = { confirmPrepared = true },
                    )
                    HorizontalDivider()

                    when {
                        recipe == null -> ChefMessage(
                            icon = Icons.Outlined.Restaurant,
                            title = "Recipe not found",
                            body = "This recipe is no longer in your library.",
                            onExit = onExit,
                        )
                        // Nothing to step through, so the ingredients stop being a sidebar and become
                        // the whole point — better than an empty screen with a button on it.
                        steps.isEmpty() && ingredients.isNotEmpty() -> ChefIngredients(
                            ingredients = ingredients,
                            scale = scale,
                            showTitle = true,
                            isChecked = progress::isIngredientChecked,
                            onToggle = { updateSession(progress.toggleIngredient(it)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        steps.isEmpty() -> ChefMessage(
                            icon = Icons.AutoMirrored.Outlined.ListAlt,
                            title = "Nothing to cook from",
                            body = "This recipe has no ingredients and no directions yet.",
                            onExit = onExit,
                        )
                        else -> Row(Modifier.fillMaxSize()) {
                            if (ingredientsPane) {
                                ChefIngredients(
                                    ingredients = ingredients,
                                    scale = scale,
                                    showTitle = true,
                                    isChecked = progress::isIngredientChecked,
                                    onToggle = { updateSession(progress.toggleIngredient(it)) },
                                    modifier = Modifier.width(paneWidth).fillMaxHeight(),
                                )
                                VerticalDivider()
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (style) {
                                        ChefDisplayStyle.AllSteps -> ChefAllSteps(
                                            steps = steps,
                                            progress = progress,
                                            scale = scale,
                                            // The session's own id, not the current step: this is null
                                            // until the cook actually picks or advances one, and that
                                            // is exactly when the list should start following along.
                                            selectedStepId = session.currentStepId,
                                            onSelect = { updateSession(progress.select(it)) },
                                            onToggleCompleted = { updateSession(progress.toggleCompleted(it)) },
                                        )
                                        ChefDisplayStyle.OneStep -> ChefOneStep(
                                            progress = progress,
                                            scale = scale,
                                            onPrevious = { updateSession(progress.previous()) },
                                            onNext = { updateSession(progress.next()) },
                                        )
                                    }
                                }
                                HorizontalDivider()
                                ChefControlsBar(
                                    progress = progress,
                                    compact = !roomForName,
                                    onPrevious = { updateSession(progress.previous()) },
                                    onNext = { updateSession(progress.next()) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIngredientsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { showIngredientsSheet = false }, sheetState = sheetState) {
            ChefIngredients(
                ingredients = ingredients,
                scale = scale,
                // The sheet carries only a drag handle, so the list still has to say what it is.
                showTitle = true,
                isChecked = progress::isIngredientChecked,
                onToggle = { updateSession(progress.toggleIngredient(it)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    // Asked rather than stamped outright: the control sits in a menu that gets opened with messy hands
    // mid-cook, and a stray tap would otherwise silently overwrite a real last-prepared date with today's.
    if (confirmPrepared) {
        AlertDialog(
            onDismissRequest = { confirmPrepared = false },
            title = { Text("Mark as prepared?") },
            text = { Text("Set this recipe's \"Last Prepared\" date to today?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrepared = false
                    val now = nowTimestamp()
                    module.localStore.setRecipePrepared(id, now, now)
                    module.onLocalChange()
                    didMarkPrepared = true
                }) { Text("Mark as Prepared") }
            },
            // Named for what it does to the data rather than "Cancel": the whole job of this dialog is
            // to make clear that confirming overwrites a date, so the way out should say it doesn't.
            dismissButton = { TextButton(onClick = { confirmPrepared = false }) { Text("Do Not Change") } },
        )
    }
}

/**
 * Below this the ingredients move from a pinned pane to a sheet; see the call site. Comfortably above
 * the point at which [chefIngredientsPaneWidthDp]'s floor would start fighting its cap.
 */
private val CHEF_PANE_MIN_WIDTH = 720.dp

/**
 * Below this the header drops the recipe name and the display-mode toggle, and the controls bar drops
 * its button titles. A phone header cannot carry Exit, a name, and five controls; the name is what
 * gives, because Chef Mode is only ever entered from the recipe it is showing.
 */
private val CHEF_HEADER_NAME_MIN_WIDTH = 600.dp

/** Longest comfortable measure for a giant step; past this One Step mode centres rather than stretches. */
private val CHEF_ONE_STEP_WIDTH = 900.dp

/**
 * The top bar: leave, what you are cooking, and the controls that change how it is presented.
 *
 * Deliberately outside the text-size scale the content carries — the chrome should stay a predictable
 * size however far the recipe text is dialled up, or the bar alone would fill a phone screen.
 */
@Composable
private fun ChefHeaderBar(
    recipeName: String,
    showName: Boolean,
    showStyleToggle: Boolean,
    /** Whether the display modes are worth offering at all — false for a recipe with no directions. */
    offerDisplayStyle: Boolean,
    showIngredientsButton: Boolean,
    style: ChefDisplayStyle,
    textLevel: Int,
    hasProgress: Boolean,
    didMarkPrepared: Boolean,
    canMarkPrepared: Boolean,
    onExit: () -> Unit,
    onIngredients: () -> Unit,
    onStyle: (ChefDisplayStyle) -> Unit,
    onTextLevel: (Int) -> Unit,
    onStartOver: () -> Unit,
    onMarkPrepared: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        // Inset from the corner rather than flush against it: a control jammed into the screen edge is
        // the hardest thing to hit on a touchscreen, and Exit is the one that must never be missed.
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Exit")
        }

        if (showName) {
            Text(
                recipeName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        if (showIngredientsButton) {
            ChefHeaderIcon(Icons.Outlined.Checklist, "Ingredients", onClick = onIngredients)
        }
        ChefHeaderIcon(
            Icons.Outlined.TextDecrease,
            "Smaller text",
            enabled = textLevel > ChefTextSize.minLevel,
            stateDescription = ChefTextSize.accessibilityValue(textLevel),
            onClick = { onTextLevel(textLevel - 1) },
        )
        ChefHeaderIcon(
            Icons.Outlined.TextIncrease,
            "Larger text",
            enabled = textLevel < ChefTextSize.maxLevel,
            stateDescription = ChefTextSize.accessibilityValue(textLevel),
            onClick = { onTextLevel(textLevel + 1) },
        )
        if (showStyleToggle) {
            ChefHeaderIcon(
                style.toggled.icon,
                "Show ${style.toggled.displayName}",
                onClick = { onStyle(style.toggled) },
            )
        }

        Box {
            ChefHeaderIcon(Icons.Outlined.MoreVert, "More options", onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Where the display modes live on a phone, which has no room for the toggle above.
                if (offerDisplayStyle && !showStyleToggle) {
                    ChefDisplayStyle.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            leadingIcon = { Icon(option.icon, contentDescription = null) },
                            trailingIcon = {
                                if (option == style) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                            },
                            onClick = { menu = false; onStyle(option) },
                        )
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    // Settles into a done state rather than staying a greyed-out invitation to do
                    // something that has already happened.
                    text = { Text(if (didMarkPrepared) "Prepared Today" else "Mark as Prepared…") },
                    leadingIcon = { Icon(Icons.Outlined.EventAvailable, contentDescription = null) },
                    enabled = canMarkPrepared && !didMarkPrepared,
                    onClick = { menu = false; onMarkPrepared() },
                )
                DropdownMenuItem(
                    text = { Text("Start Over") },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    enabled = hasProgress,
                    onClick = { menu = false; onStartOver() },
                )
            }
        }
    }
}

/**
 * One header-bar control: an icon in a fixed square.
 *
 * Fixed, because these glyphs are nowhere near the same shape — a row of dots next to a tall A — and
 * left to size themselves they come out visibly different next to each other. The square is what makes
 * them one matching set, and it gives every one the same target however small its glyph is.
 */
@Composable
private fun ChefHeaderIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    stateDescription: String? = null,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .then(stateDescription?.let { s -> Modifier.semantics { this.stateDescription = s } } ?: Modifier),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(26.dp))
    }
}

/** The icon each display mode is offered by. */
private val ChefDisplayStyle.icon: ImageVector
    get() = when (this) {
        ChefDisplayStyle.AllSteps -> Icons.AutoMirrored.Outlined.ListAlt
        ChefDisplayStyle.OneStep -> Icons.Outlined.ViewAgenda
    }

/**
 * The ingredients: a large-print, checkable list. Pinned to the left where there is room, behind a
 * sheet where there isn't.
 */
@Composable
private fun ChefIngredients(
    ingredients: List<Ingredient>,
    scale: Float,
    showTitle: Boolean,
    isChecked: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showTitle) {
                item {
                    Text(
                        "Ingredients",
                        style = MaterialTheme.typography.headlineSmall.scaled(scale),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(ingredients) { index, ingredient ->
                if (ingredient.isHeading) {
                    Text(
                        ingredient.text,
                        style = MaterialTheme.typography.titleLarge.scaled(scale),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                } else {
                    ChefCheckRow(
                        checked = isChecked(index),
                        onToggle = { onToggle(index) },
                        label = ingredient.text,
                        scale = scale,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            ingredientLine(ingredient.text),
                            style = MaterialTheme.typography.titleLarge.scaled(scale),
                        )
                    }
                }
            }
        }
        EdgeScrollbar(listState)
    }
}

/**
 * The default presentation: every step visible at once, the current one highlighted and pulled to the
 * middle of the screen. A strict one-step-at-a-time view answers "what's next?" but not "what did step
 * 4 say?"; this keeps both, and most of the focus benefit.
 */
@Composable
private fun ChefAllSteps(
    steps: List<ChefStep>,
    progress: ChefProgress,
    scale: Float,
    /** The step the cook has actually landed on, or null while they are still at the start. */
    selectedStepId: Int?,
    onSelect: (ChefStep) -> Unit,
    onToggleCompleted: (ChefStep) -> Unit,
) {
    val listState = rememberLazyListState()

    // Deliberately keyed on the *selected* step rather than the current one, which falls back to step
    // one and is therefore never null: on first entry the list is already at the top, where step one
    // is, and centring it there would push a leading section heading off the top for no reason.
    // Re-runs on the text size too, so growing the type doesn't lose the cook's place.
    LaunchedEffect(selectedStepId, scale, steps) {
        val target = selectedStepId ?: return@LaunchedEffect
        val index = steps.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { } // let the list lay out, so the viewport below is a real measurement
        val info = listState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val itemHeight = info.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
        listState.animateScrollToItem(index, -((viewport - itemHeight) / 2).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(steps, key = { _, step -> step.id }) { _, step ->
                if (step.isHeading) {
                    Text(
                        step.text,
                        style = MaterialTheme.typography.headlineSmall.scaled(scale),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                } else {
                    ChefStepRow(
                        step = step,
                        isCurrent = progress.isCurrent(step),
                        isCompleted = progress.isCompleted(step),
                        scale = scale,
                        onSelect = { onSelect(step) },
                        onToggleCompleted = { onToggleCompleted(step) },
                    )
                }
            }
            // Lets the last step still settle in the middle of the screen when it becomes current.
            item { Spacer(Modifier.height(240.dp)) }
        }
        EdgeScrollbar(listState)
    }
}

/**
 * One step in the continuous list.
 *
 * The current step is a size up and sits on its own card; every other step — done or still to come —
 * reads at full contrast, because a dimmed step is exactly the one being squinted at from across the
 * kitchen. Completion is said with the checkmark instead.
 */
@Composable
private fun ChefStepRow(
    step: ChefStep,
    isCurrent: Boolean,
    isCompleted: Boolean,
    scale: Float,
    onSelect: () -> Unit,
    onToggleCompleted: () -> Unit,
) {
    val style = (if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        .scaled(scale)
    val background = if (isCurrent) {
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    } else {
        Modifier
    }
    ChefCheckRow(
        checked = isCompleted,
        onToggle = onToggleCompleted,
        label = step.number?.let { "Step $it. ${step.text}" } ?: step.text,
        scale = scale,
        modifier = background.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        onClickText = onSelect,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            step.number?.let {
                Text("$it.", style = style, fontWeight = FontWeight.Bold)
            }
            Text(step.text, style = style)
        }
    }
}

/**
 * A big checkable row: the circle toggles, the rest of the row does whatever the caller says (nothing,
 * for an ingredient; "make this the current step", for a direction). The whole row is the target either
 * way — aiming at text is not something to ask of someone holding a wooden spoon.
 */
@Composable
private fun ChefCheckRow(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    scale: Float,
    modifier: Modifier = Modifier,
    onClickText: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Grows with the text, but nowhere near as fast: at 3x the type this would otherwise be a 72dp
    // bullseye next to the words it belongs to.
    val iconSize = (26f * (1f + (scale - 1f) * 0.45f)).dp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(iconSize + 22.dp)
                .semantics { stateDescription = if (checked) "Checked" else "Not checked" },
        ) {
            Icon(
                if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = label,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(iconSize),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .then(
                    if (onClickText != null) {
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClickText)
                    } else {
                        Modifier
                    },
                )
                // Lines the text up with the middle of the circle beside it rather than the top of it.
                .padding(vertical = (iconSize.value * 0.22f).dp, horizontal = 4.dp),
        ) {
            content()
        }
    }
}

/**
 * One giant step at a time, for the two situations the continuous list handles worst: read from across
 * the kitchen, and hands too messy to aim at anything small. The outer thirds of the screen are the
 * previous/next controls — no target to hit — and the middle third still scrolls a long step.
 */
@Composable
private fun ChefOneStep(
    progress: ChefProgress,
    scale: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val step = progress.currentStep
    if (step == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.ListAlt,
                title = "No steps",
                body = "This recipe has no numbered directions.",
            )
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        // Keyed on the step, so a long step scrolled to the bottom doesn't hand the next one on screen
        // already scrolled past its own first line.
        key(step.id) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = CHEF_ONE_STEP_WIDTH).padding(horizontal = 32.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    progress.currentSectionHeading?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleLarge.scaled(scale),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    step.number?.let {
                        Text(
                            "$it.",
                            style = MaterialTheme.typography.headlineMedium.scaled(scale),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(step.text, style = MaterialTheme.typography.displaySmall.scaled(scale))
                }
            }
        }
        // Above the text: a tap anywhere in the outer third moves a step. The middle stays clear so a
        // long step can still be scrolled, and so does nothing when a move isn't available.
        Row(Modifier.fillMaxSize()) {
            ChefTapZone("Previous step", enabled = progress.canGoToPreviousStep, onClick = onPrevious)
            Spacer(Modifier.weight(1f).fillMaxHeight())
            ChefTapZone("Next step", enabled = progress.canAdvance, onClick = onNext)
        }
    }
}

@Composable
private fun RowScope.ChefTapZone(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
    )
}

/**
 * The bottom bar, shared by both display modes: step back, where you are, step forward.
 *
 * "Next" completes the step on its way past, so working through a recipe with this bar alone leaves the
 * check-offs correct without any extra tapping. It stays put all the way through and simply disables
 * once the last step is done — the buttons shouldn't move or swap out under hands that have learned
 * where they are.
 */
@Composable
private fun ChefControlsBar(
    progress: ChefProgress,
    compact: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = progress.canGoToPreviousStep,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = if (compact) "Previous step" else null)
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text("Previous")
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            // Says the recipe is done rather than leaving "Step 9 of 9" up with both buttons dead and
            // nothing to explain why.
            if (progress.isFinished) "All steps done" else progress.progressLabel.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            enabled = progress.canAdvance,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            if (!compact) {
                Text("Next")
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = if (compact) "Next step" else null)
        }
    }
}

/** A filled content area saying why there's nothing to cook from, with the way out repeated. */
@Composable
private fun ChefMessage(icon: ImageVector, title: String, body: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(icon = icon, title = title, body = body, actionLabel = "Exit", onAction = onExit)
    }
}
