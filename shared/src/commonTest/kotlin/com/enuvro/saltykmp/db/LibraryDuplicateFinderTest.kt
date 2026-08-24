package com.enuvro.saltykmp.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grouping half of the post-sync fold. Port of Salty's LibraryDuplicateTests; the merging half
 * needs a real database and lives in jvmTest's LibraryDuplicateMergerTest.
 */
class LibraryDuplicateFinderTest {

    private fun item(id: String, name: String, recipeCount: Int = 0) = LibraryClassifierItem(id, name, recipeCount)

    @Test
    fun namesGroupIgnoringCaseAndWhitespaceButNotAccents() {
        val items = listOf(
            item("1", "Main dish"),
            item("2", "  main   DISH "),
            item("3", "Main Dishes"),
            item("4", "Crème"),
            item("5", "Creme"),
        )

        val groups = LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, items)

        assertEquals(1, groups.size)
        assertEquals("1", groups[0].survivor.id)
        assertEquals(listOf("2"), groups[0].duplicates.map { it.id })
    }

    @Test
    fun blankNamesAreNeverGroupedTogether() {
        val items = listOf(item("1", ""), item("2", "   "), item("3", "\t"))

        assertTrue(
            LibraryDuplicateFinder.groups(LibraryClassifier.TAG, items).isEmpty(),
            "an empty name is no evidence that two rows are the same thing",
        )
    }

    @Test
    fun mostRecipesKeepsTheBusiestRowAndOldestIdIgnoresCounts() {
        val items = listOf(item("A", "Breads", 1), item("B", "breads", 9))

        assertEquals(
            "B",
            LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, items, SurvivorRule.MOST_RECIPES)[0].survivor.id,
        )
        assertEquals(
            "A",
            LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, items, SurvivorRule.OLDEST_ID)[0].survivor.id,
            "only an id-based rule makes two devices pick the same winner",
        )
    }

    @Test
    fun aNameHeldByOneRowIsNotAGroup() {
        val items = listOf(item("1", "Breads"), item("2", "Breakfast"))

        assertTrue(LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, items).isEmpty())
    }

    @Test
    fun threeSameNamedRowsMakeOneGroupWithTwoDuplicates() {
        val items = listOf(item("C", " BREADS ", 5), item("A", "Breads", 1), item("B", "breads", 3))

        val group = LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, items, SurvivorRule.OLDEST_ID).single()

        assertEquals("A", group.survivor.id)
        assertEquals(listOf("B", "C"), group.duplicates.map { it.id })
        assertEquals(2, group.removedCount)
    }
}
