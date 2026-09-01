package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.model.Direction

/**
 * Chef Mode's model layer — a port of the Swift app's `ChefStep`, `ChefSessionState`,
 * `ChefViewSessionStore` and the pure half of its `ChefViewModel`.
 *
 * Everything here is plain Kotlin: no Compose, no database, no coroutines. Chef Mode is pure
 * presentation over a recipe that has already been read, so the only state it owns is "where am I in
 * this recipe" — and keeping that state a value means the step arithmetic (which step is current, what
 * "Next" does on the last one, what stepping back un-does) can be tested without standing up a UI.
 *
 * See [ChefScreen] for the view.
 */

/**
 * One row of a recipe's directions as Chef Mode shows it.
 *
 * Identified by its index in the recipe's `directions` list rather than by [Direction.id], which comes
 * from imported files and isn't guaranteed unique within a recipe.
 */
internal data class ChefStep(
    val id: Int,
    val text: String,
    val isHeading: Boolean,
    /** 1-based cook step number, counting only non-heading rows. Null for headings. */
    val number: Int?,
)

/**
 * Builds Chef Mode's rows from a recipe's directions.
 *
 * Headings are kept — they orient the cook — but aren't numbered, matching the count-the-non-headings
 * convention the recipe detail screen already uses. Blank rows (usually a direction added in the editor
 * and never typed into) are dropped rather than rendered as an empty giant step in One Step mode.
 */
internal fun chefSteps(directions: List<Direction>?): List<ChefStep> {
    val steps = mutableListOf<ChefStep>()
    var number = 0
    directions.orEmpty().forEachIndexed { index, direction ->
        val text = direction.text.trim()
        if (text.isEmpty()) return@forEachIndexed
        val isHeading = direction.isHeading == true
        if (!isHeading) number++
        steps += ChefStep(id = index, text = text, isHeading = isHeading, number = if (isHeading) null else number)
    }
    return steps
}

/**
 * Cooking progress for one recipe: the step being worked on, what's been checked off, and which
 * ingredients have been gathered.
 *
 * Deliberately never written to the database — no migration, no sync, and no "is this progress from
 * three weeks ago?" question. [ChefSessionStore] holds it for the length of the app session and no
 * longer.
 */
internal data class ChefSessionState(
    /** [ChefStep.id] of the step being worked on; null until one is picked (see [ChefProgress.currentStep]). */
    val currentStepId: Int? = null,
    val completedStepIds: Set<Int> = emptySet(),
    /** Indices into the recipe's `ingredients` list. */
    val checkedIngredientIds: Set<Int> = emptySet(),
) {
    val isPristine: Boolean
        get() = currentStepId == null && completedStepIds.isEmpty() && checkedIngredientIds.isEmpty()
}

/**
 * Chef Mode progress, keyed by recipe id and held for the app session by [AppModule].
 *
 * App-level rather than per-screen so that leaving Chef Mode to glance at the recipe (or at another
 * recipe) and coming back returns to the same checked ingredients and the same current step.
 */
internal class ChefSessionStore {
    private val sessions = mutableMapOf<String, ChefSessionState>()

    /** Progress for a recipe — a pristine session for one that hasn't been cooked yet. */
    fun state(recipeId: String): ChefSessionState = sessions[recipeId] ?: ChefSessionState()

    /**
     * Records a recipe's progress. A session that is back to pristine is dropped rather than stored, so
     * the map doesn't accumulate an empty entry for every recipe that was merely opened.
     */
    fun save(recipeId: String, state: ChefSessionState) {
        if (state.isPristine) sessions.remove(recipeId) else sessions[recipeId] = state
    }

    fun reset(recipeId: String) {
        sessions.remove(recipeId)
    }

    /** True when there's progress worth returning to; drives whether "Start Over" is offered. */
    fun hasProgress(recipeId: String): Boolean = !state(recipeId).isPristine
}

/**
 * A recipe's steps and one [ChefSessionState] read together: which step is current, what's done, and
 * what each control does next.
 *
 * Every move returns a *new* [ChefSessionState] rather than mutating anything, so the screen can hold
 * the session in one `mutableStateOf` and this whole class stays a pure function of its inputs.
 */
internal class ChefProgress(private val steps: List<ChefStep>, private val state: ChefSessionState) {

    /** Just the numbered steps: headings orient the cook but are never current and can't be stepped onto. */
    val cookableSteps: List<ChefStep> = steps.filter { !it.isHeading }

    val totalStepCount: Int get() = cookableSteps.size

    /**
     * The step in focus. Falls back to the first cookable step so Chef Mode always opens on something
     * without having to write that choice into the session up front.
     */
    val currentStep: ChefStep? =
        state.currentStepId?.let { id -> cookableSteps.firstOrNull { it.id == id } } ?: cookableSteps.firstOrNull()

    /** "Step 3 of 9" — null when the recipe has no numbered steps to count. */
    val progressLabel: String?
        get() {
            val number = currentStep?.number ?: return null
            return if (totalStepCount == 0) null else "Step $number of $totalStepCount"
        }

    /**
     * The section heading the current step falls under, if the recipe uses them. One Step mode shows it
     * above the step, since it never renders heading rows of its own.
     */
    val currentSectionHeading: String?
        get() {
            val current = currentStep ?: return null
            return steps.lastOrNull { it.isHeading && it.id < current.id }?.text
        }

    fun isCurrent(step: ChefStep): Boolean = !step.isHeading && step.id == currentStep?.id

    fun isCompleted(step: ChefStep): Boolean = step.id in state.completedStepIds

    fun isIngredientChecked(index: Int): Boolean = index in state.checkedIngredientIds

    val canGoToPreviousStep: Boolean
        get() = currentStep != null && cookableSteps.firstOrNull()?.id != currentStep.id

    /**
     * True while "Next" still has something to do: another step to move onto, or a current step not yet
     * checked off. Only false once the last step is done — so the button can be disabled in place rather
     * than swapped out, and the cook is never left on a final step with no way to complete it.
     */
    val canAdvance: Boolean
        get() {
            val current = currentStep ?: return false
            return cookableSteps.lastOrNull()?.id != current.id || current.id !in state.completedStepIds
        }

    /** Every numbered step checked off. */
    val isFinished: Boolean
        get() = cookableSteps.isNotEmpty() && cookableSteps.all { it.id in state.completedStepIds }

    val hasProgress: Boolean get() = !state.isPristine

    /** Makes a step current. Headings are ignored, so tapping one can't strand the cook on a row with
     *  no next/previous meaning. */
    fun select(step: ChefStep): ChefSessionState =
        if (step.isHeading) state else state.copy(currentStepId = step.id)

    /**
     * Completes the current step and moves on. On the last step it completes without moving, which is
     * what flips [isFinished].
     */
    fun next(): ChefSessionState {
        val current = currentStep ?: return state
        val position = cookableSteps.indexOfFirst { it.id == current.id }
        if (position < 0) return state
        val following = cookableSteps.getOrNull(position + 1)
        return state.copy(
            currentStepId = following?.id ?: current.id,
            completedStepIds = state.completedStepIds + current.id,
        )
    }

    /**
     * Steps back and un-completes the step landed on — going back means redoing it, and without this,
     * stepping back and forth would leave everything marked done.
     */
    fun previous(): ChefSessionState {
        val current = currentStep ?: return state
        val position = cookableSteps.indexOfFirst { it.id == current.id }
        if (position <= 0) return state
        val preceding = cookableSteps[position - 1]
        return state.copy(
            currentStepId = preceding.id,
            completedStepIds = state.completedStepIds - preceding.id - current.id,
        )
    }

    fun toggleCompleted(step: ChefStep): ChefSessionState = when {
        step.isHeading -> state
        step.id in state.completedStepIds -> state.copy(completedStepIds = state.completedStepIds - step.id)
        else -> state.copy(completedStepIds = state.completedStepIds + step.id)
    }

    fun toggleIngredient(index: Int): ChefSessionState =
        if (index in state.checkedIngredientIds) {
            state.copy(checkedIngredientIds = state.checkedIngredientIds - index)
        } else {
            state.copy(checkedIngredientIds = state.checkedIngredientIds + index)
        }
}

/**
 * Width of the pinned ingredients pane, in dp, for a window [windowWidthDp] wide at text scale
 * [textScale]. Only meaningful above the threshold at which [ChefScreen] pins the pane at all.
 *
 * It grows with the type because a comfortable measure has to: a third of a tablet holds an
 * ingredient line at normal size and about eight characters at three times it. The cap is applied
 * before the floor, and deliberately not as a single `coerceIn` — on a phone-width window the cap
 * lands *below* the floor, and `coerceIn` throws when handed a range in that order.
 */
internal fun chefIngredientsPaneWidthDp(windowWidthDp: Float, textScale: Float): Float =
    (windowWidthDp * CHEF_PANE_FRACTION * textScale)
        .coerceAtMost(windowWidthDp * CHEF_PANE_MAX_FRACTION)
        .coerceAtLeast(CHEF_PANE_MIN_DP)

/** Share of the window the ingredients take at the smallest text size, and the ceiling on it. */
private const val CHEF_PANE_FRACTION = 0.30f
private const val CHEF_PANE_MAX_FRACTION = 0.45f
private const val CHEF_PANE_MIN_DP = 260f

/** How Chef Mode presents the directions. Persisted, unlike the cooking progress itself. */
internal enum class ChefDisplayStyle(val displayName: String) {
    /** Every step visible at once with the current one highlighted. The default: a strict
     *  one-step-at-a-time view answers "what's next?" but not "what did step 4 say?". */
    AllSteps("All Steps"),

    /** One giant step at a time, with big tap zones. Best across a kitchen and with messy hands. */
    OneStep("One Step"),
    ;

    val toggled: ChefDisplayStyle get() = if (this == AllSteps) OneStep else AllSteps
}

/**
 * Chef Mode's text-size stepper.
 *
 * The Swift app steps through Dynamic Type positions; Compose has no equivalent, so the levels are
 * plain multipliers applied to the Material type styles Chef Mode already picks (see `chefStyle` in
 * [ChefScreen]). The ramp reaches 3x because "legible from across the kitchen" is a long way past
 * normal, and it starts at 1.0 because Chef Mode is never the place to shrink text below the sizes it
 * has already chosen — the smallest step here is still larger than the recipe detail screen's body.
 */
internal object ChefTextSize {
    private val scales = listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f, 2.3f, 2.65f, 3.0f)

    /** A step or two above the base, so Chef Mode opens visibly bigger than the recipe it came from. */
    const val DEFAULT_LEVEL = 2

    val minLevel = 0
    val maxLevel = scales.lastIndex
    val levelCount = scales.size

    fun clamped(level: Int): Int = level.coerceIn(minLevel, maxLevel)

    fun scale(level: Int): Float = scales[clamped(level)]

    /** For the stepper buttons' accessibility value, which are otherwise two unlabelled A's. */
    fun accessibilityValue(level: Int): String = "Text size ${clamped(level) + 1} of $levelCount"
}
