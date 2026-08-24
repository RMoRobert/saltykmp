package com.enuvro.saltykmp.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeSearchTest {

    private val recipe = RecipeSearchFields(
        name = "Chicken Soup",
        introduction = "A comforting winter bowl",
        ingredients = listOf("2 lb chicken thighs", "1 onion, diced"),
        notes = listOf("Freezes well"),
        variations = listOf("Add rice for a heartier bowl"),
        courseName = "Main Dish",
        categoryNames = listOf("Soups", "Weeknight"),
        tagNames = listOf("comfort food"),
    )

    private fun matches(query: String, vararg fields: RecipeSearchField) =
        RecipeSearch.matches(recipe, query, fields.toSet())

    @Test fun blankQueryMatchesEverything() {
        assertTrue(matches("", RecipeSearchField.NAME))
        assertTrue(matches("   ", RecipeSearchField.NAME))
        // Even with no options enabled at all.
        assertTrue(RecipeSearch.matches(recipe, "", emptySet()))
    }

    @Test fun nameMatchesAsCaseInsensitiveSubstring() {
        assertTrue(matches("chicken", RecipeSearchField.NAME))
        assertTrue(matches("CHICKEN", RecipeSearchField.NAME))
        assertTrue(matches("ken sou", RecipeSearchField.NAME)) // substring, not word-prefix
        assertFalse(matches("beef", RecipeSearchField.NAME))
    }

    @Test fun queryIsTrimmedBeforeMatching() {
        assertTrue(matches("  chicken  ", RecipeSearchField.NAME))
    }

    @Test fun onlyEnabledFieldsAreSearched() {
        // "onion" appears only in the ingredients.
        assertFalse(matches("onion", RecipeSearchField.NAME))
        assertTrue(matches("onion", RecipeSearchField.INGREDIENTS))
        assertTrue(matches("onion", RecipeSearchField.NAME, RecipeSearchField.INGREDIENTS))
    }

    @Test fun eachFieldIsReachable() {
        assertTrue(matches("comforting", RecipeSearchField.INTRODUCTION))
        assertTrue(matches("freezes", RecipeSearchField.NOTES))
        assertTrue(matches("heartier", RecipeSearchField.VARIATIONS))
        assertTrue(matches("main dish", RecipeSearchField.COURSE))
        assertTrue(matches("weeknight", RecipeSearchField.CATEGORY))
        assertTrue(matches("comfort", RecipeSearchField.TAGS))
    }

    @Test fun fieldsCombineAsOrNotAnd() {
        // Matching ONE enabled field is enough, even though the term is absent from the other.
        assertTrue(matches("soups", RecipeSearchField.NAME, RecipeSearchField.CATEGORY))
    }

    @Test fun multiWordQueryIsOneSubstringNotPerWordAnd() {
        // Mirrors the Swift app's single LIKE '%…%' pattern: the words must be adjacent, in order.
        assertTrue(matches("chicken soup", RecipeSearchField.NAME))
        assertFalse(matches("soup chicken", RecipeSearchField.NAME))
        // ...and a query spanning two different fields matches neither.
        assertFalse(matches("chicken onion", RecipeSearchField.NAME, RecipeSearchField.INGREDIENTS))
    }

    @Test fun emptyOptionsFallBackToNameSearch() {
        assertTrue(RecipeSearch.matches(recipe, "chicken", emptySet()))
        assertFalse(RecipeSearch.matches(recipe, "onion", emptySet()))
    }

    @Test fun missingFieldsNeverMatch() {
        val bare = RecipeSearchFields(name = "Toast")
        assertFalse(RecipeSearch.matches(bare, "anything", RecipeSearchField.entries.toSet()))
        assertTrue(RecipeSearch.matches(bare, "toast", RecipeSearchField.entries.toSet()))
    }
}
