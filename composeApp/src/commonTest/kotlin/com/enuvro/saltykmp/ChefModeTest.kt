package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins Chef Mode's step arithmetic — the counterpart of the Swift app's ChefStepTests, plus the
 * step-through behaviour its ChefViewModel owns.
 *
 * All of it is a pure function of (directions, session), which is the reason [ChefMode] keeps the
 * model layer out of Compose: what "Next" does on the last step, and what stepping back un-does, are
 * exactly the parts worth holding still.
 */
class ChefModeTest {

    private fun direction(text: String, heading: Boolean = false) =
        Direction(id = "d-$text", isHeading = if (heading) true else null, text = text)

    private val withHeadings = listOf(
        direction("For the Dough", heading = true),
        direction("Mix the flour and water."),
        direction("Knead for ten minutes."),
        direction("For the Sauce", heading = true),
        direction("Simmer the tomatoes."),
    )

    private fun progress(
        directions: List<Direction> = withHeadings,
        state: ChefSessionState = ChefSessionState(),
    ) = ChefProgress(chefSteps(directions), state)

    // ---- Steps ----

    @Test
    fun numbers_only_non_heading_rows() {
        val steps = chefSteps(withHeadings)
        assertEquals(listOf(null, 1, 2, null, 3), steps.map { it.number })
        assertEquals(listOf(true, false, false, true, false), steps.map { it.isHeading })
    }

    /** Direction.id comes from imported files and isn't guaranteed unique, so identity is the index —
     *  including across headings, which keeps the ids lined up with the recipe's directions list. */
    @Test
    fun identifies_steps_by_position_in_the_directions_list() {
        assertEquals(listOf(0, 1, 2, 3, 4), chefSteps(withHeadings).map { it.id })
    }

    @Test
    fun drops_blank_rows_and_keeps_numbering_contiguous() {
        val steps = chefSteps(
            listOf(direction("Chop the onion."), direction("   "), direction(""), direction("Fry the onion.")),
        )
        assertEquals(listOf(1, 2), steps.map { it.number })
        assertEquals(listOf(0, 3), steps.map { it.id })
    }

    @Test
    fun a_recipe_with_no_directions_has_no_steps() {
        assertEquals(emptyList(), chefSteps(null))
        assertEquals(emptyList(), chefSteps(emptyList()))
    }

    // ---- Where you are ----

    /** So Chef Mode always opens on something without writing that choice into the session up front. */
    @Test
    fun the_current_step_falls_back_to_the_first_cookable_one() {
        val p = progress()
        assertEquals(1, p.currentStep?.number)
        assertEquals("Step 1 of 3", p.progressLabel)
        assertFalse(p.canGoToPreviousStep)
        assertFalse(p.hasProgress)
    }

    @Test
    fun the_progress_label_counts_only_cookable_steps() {
        val p = progress(state = ChefSessionState(currentStepId = 4))
        assertEquals("Step 3 of 3", p.progressLabel)
    }

    @Test
    fun the_section_heading_is_the_nearest_one_above_the_current_step() {
        assertEquals("For the Dough", progress(state = ChefSessionState(currentStepId = 2)).currentSectionHeading)
        assertEquals("For the Sauce", progress(state = ChefSessionState(currentStepId = 4)).currentSectionHeading)
        assertNull(progress(listOf(direction("Boil water."))).currentSectionHeading)
    }

    /** Tapping a heading must not strand the cook on a row with no next/previous meaning. */
    @Test
    fun headings_cannot_be_selected_or_completed() {
        val p = progress()
        val heading = chefSteps(withHeadings).first { it.isHeading }
        assertEquals(ChefSessionState(), p.select(heading))
        assertEquals(ChefSessionState(), p.toggleCompleted(heading))
        assertFalse(p.isCurrent(heading))
    }

    // ---- Stepping through ----

    @Test
    fun next_completes_the_current_step_on_its_way_past() {
        val after = progress().next()
        assertEquals(setOf(1), after.completedStepIds)
        assertEquals(2, after.currentStepId)
    }

    /** The last step still completes, which is what flips the finished state — the cook is never left
     *  on a final step with no way to check it off. */
    @Test
    fun next_on_the_last_step_completes_without_moving_and_then_stops() {
        val onLast = ChefSessionState(currentStepId = 4, completedStepIds = setOf(1, 2))
        assertTrue(progress(state = onLast).canAdvance)

        val after = progress(state = onLast).next()
        assertEquals(4, after.currentStepId)
        assertEquals(setOf(1, 2, 4), after.completedStepIds)

        val finished = progress(state = after)
        assertTrue(finished.isFinished)
        assertFalse(finished.canAdvance)
        assertTrue(finished.canGoToPreviousStep)
    }

    /** Going back means redoing the step; without un-completing, stepping back and forth would leave
     *  everything marked done. */
    @Test
    fun previous_un_completes_the_step_it_lands_on_and_the_one_it_left() {
        val state = ChefSessionState(currentStepId = 2, completedStepIds = setOf(1, 2))
        val after = progress(state = state).previous()
        assertEquals(1, after.currentStepId)
        assertEquals(emptySet(), after.completedStepIds)
    }

    @Test
    fun previous_on_the_first_step_does_nothing() {
        val state = ChefSessionState(currentStepId = 1, completedStepIds = setOf(1))
        assertEquals(state, progress(state = state).previous())
        assertFalse(progress(state = state).canGoToPreviousStep)
    }

    @Test
    fun completing_every_step_out_of_order_still_finishes() {
        val p = progress(state = ChefSessionState(completedStepIds = setOf(1, 2, 4)))
        assertTrue(p.isFinished)
    }

    @Test
    fun a_recipe_of_headings_alone_is_never_finished() {
        val p = progress(listOf(direction("For the Dough", heading = true)))
        assertFalse(p.isFinished)
        assertFalse(p.canAdvance)
        assertNull(p.currentStep)
        assertNull(p.progressLabel)
    }

    @Test
    fun ingredients_toggle_independently_of_the_steps() {
        val p = progress()
        val checked = p.toggleIngredient(2)
        assertEquals(setOf(2), checked.checkedIngredientIds)
        assertTrue(ChefProgress(chefSteps(withHeadings), checked).isIngredientChecked(2))
        assertEquals(emptySet(), ChefProgress(chefSteps(withHeadings), checked).toggleIngredient(2).checkedIngredientIds)
    }

    // ---- The session store ----

    @Test
    fun the_store_keeps_progress_per_recipe_and_forgets_pristine_ones() {
        val store = ChefSessionStore()
        assertFalse(store.hasProgress("a"))

        store.save("a", ChefSessionState(currentStepId = 3))
        store.save("b", ChefSessionState(checkedIngredientIds = setOf(0)))
        assertEquals(3, store.state("a").currentStepId)
        assertEquals(setOf(0), store.state("b").checkedIngredientIds)
        assertTrue(store.hasProgress("a"))

        // Back to pristine drops the entry rather than storing an empty session for every recipe
        // that was merely opened.
        store.save("a", ChefSessionState())
        assertFalse(store.hasProgress("a"))
        assertTrue(store.hasProgress("b"))

        store.reset("b")
        assertEquals(ChefSessionState(), store.state("b"))
    }

    // ---- Display preferences ----

    @Test
    fun the_text_size_stepper_clamps_at_both_ends() {
        assertEquals(ChefTextSize.minLevel, ChefTextSize.clamped(-4))
        assertEquals(ChefTextSize.maxLevel, ChefTextSize.clamped(99))
        assertEquals(1f, ChefTextSize.scale(ChefTextSize.minLevel))
        // Chef Mode never shrinks text below the sizes it has already chosen, and the top of the ramp
        // is a long way past normal — that is the whole point of the stepper.
        assertTrue(ChefTextSize.scale(ChefTextSize.maxLevel) >= 3f)
        assertTrue(ChefTextSize.DEFAULT_LEVEL in ChefTextSize.minLevel..ChefTextSize.maxLevel)
    }

    /**
     * The regression this exists for: computing the pane width as a single `coerceIn(floor, cap)`
     * threw on every phone-width window, because 45% of 402dp is below the 260dp floor — and Chef
     * Mode computed the width whether or not it was about to pin a pane. Crashed on entry.
     */
    @Test
    fun the_ingredients_pane_width_never_throws_and_grows_with_the_text() {
        // Phone widths: never used (the pane needs 720dp), but must not blow up being calculated.
        for (width in listOf(320f, 360f, 402f, 480f, 600f, 719f)) {
            for (level in ChefTextSize.minLevel..ChefTextSize.maxLevel) {
                assertTrue(chefIngredientsPaneWidthDp(width, ChefTextSize.scale(level)) > 0f)
            }
        }

        // Where the pane is actually pinned: at least the floor, never past the cap, and wider as
        // the type grows.
        val tablet = 820f
        val small = chefIngredientsPaneWidthDp(tablet, ChefTextSize.scale(ChefTextSize.minLevel))
        val large = chefIngredientsPaneWidthDp(tablet, ChefTextSize.scale(ChefTextSize.maxLevel))
        assertTrue(small >= 260f, "floor: $small")
        assertTrue(large <= tablet * 0.45f + 0.01f, "cap: $large")
        assertTrue(large > small, "grows with the text: $small -> $large")
    }

    @Test
    fun the_display_style_toggle_round_trips() {
        assertEquals(ChefDisplayStyle.OneStep, ChefDisplayStyle.AllSteps.toggled)
        assertEquals(ChefDisplayStyle.AllSteps, ChefDisplayStyle.OneStep.toggled)
    }
}
