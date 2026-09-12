package com.enuvro.saltykmp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading back the stored sort choice, including the name an older build wrote. */
class RecipeSortTest {

    @Test
    fun aStoredChoiceComesBack() {
        assertEquals(RecipeSort.DATE_MODIFIED, RecipeSort.stored("DATE_MODIFIED"))
        assertEquals(RecipeSort.LAST_PREPARED, RecipeSort.stored("LAST_PREPARED"))
    }

    @Test
    fun theNameThisSortUsedToHaveStillSelectsIt() {
        // Written while it was called "Last Made"; anyone who left the list sorted that way keeps it.
        assertEquals(RecipeSort.LAST_PREPARED, RecipeSort.stored("LAST_MADE"))
    }

    @Test
    fun anythingUnrecognisedFallsBackToName() {
        assertEquals(RecipeSort.NAME, RecipeSort.stored(""))
        assertEquals(RecipeSort.NAME, RecipeSort.stored("BY_MOON_PHASE"))
    }
}
