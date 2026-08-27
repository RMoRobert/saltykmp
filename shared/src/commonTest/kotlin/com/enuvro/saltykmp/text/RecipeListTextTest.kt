package com.enuvro.saltykmp.text

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeListTextTest {

    // ---- Ingredients ----

    @Test
    fun eachNonBlankLineIsAnIngredient() {
        val parsed = RecipeListText.parseIngredients("2 cups flour\n1 tsp salt\n\n\n3 eggs")
        assertEquals(listOf("2 cups flour", "1 tsp salt", "3 eggs"), parsed.map { it.text })
    }

    @Test
    fun aBlankLineBeforeALineMakesItAnIngredientHeading() {
        val parsed = RecipeListText.parseIngredients("2 cups flour\n\nFor the glaze\n1 cup sugar")
        assertEquals(listOf(false, true, false), parsed.map { it.isHeading })
    }

    @Test
    fun aTrailingColonAlsoMakesAnIngredientHeadingAndTheColonIsDropped() {
        val parsed = RecipeListText.parseIngredients("For the glaze:\n1 cup sugar")
        assertEquals("For the glaze", parsed[0].text)
        assertTrue(parsed[0].isHeading)
        assertEquals(false, parsed[1].isHeading)
    }

    @Test
    fun theMainMarkerFlagsAnIngredientAndIsStrippedFromItsText() {
        val parsed = RecipeListText.parseIngredients("2 cups flour [*]\n1 tsp salt")
        assertEquals("2 cups flour", parsed[0].text)
        assertTrue(parsed[0].isMain)
        assertEquals(false, parsed[1].isMain)
    }

    @Test
    fun aHeadingIsNeverAMainIngredient() {
        // The marker means nothing on a heading — a section isn't something to buy — so it is left in
        // the text rather than silently flagging the whole section as main. (Swift does the same.)
        val parsed = RecipeListText.parseIngredients("2 cups flour\n\nFor the glaze [*]")
        assertTrue(parsed[1].isHeading)
        assertEquals(false, parsed[1].isMain)
        assertEquals("For the glaze [*]", parsed[1].text)
    }

    @Test
    fun ingredientsRoundTripThroughText() {
        val original = listOf(
            Ingredient(id = "a", text = "2 cups flour", isMain = true),
            Ingredient(id = "b", isHeading = true, text = "For the glaze"),
            Ingredient(id = "c", text = "1 cup sugar"),
        )
        val reparsed = RecipeListText.parseIngredients(RecipeListText.formatIngredients(original))
        assertEquals(original.map { Triple(it.text, it.isHeading, it.isMain) },
            reparsed.map { Triple(it.text, it.isHeading, it.isMain) })
    }

    // ---- Directions ----

    @Test
    fun aSingleBlankLineSeparatesSteps() {
        val parsed = RecipeListText.parseDirections("Whisk the dry.\n\nFold in the wet.")
        assertEquals(listOf("Whisk the dry.", "Fold in the wet."), parsed.map { it.text })
    }

    @Test
    fun consecutiveLinesJoinIntoOneStep() {
        // Text pasted from a page arrives hard-wrapped; those are one instruction, not three.
        val parsed = RecipeListText.parseDirections("Whisk the dry\ningredients together\nuntil smooth.")
        assertEquals(listOf("Whisk the dry ingredients together until smooth."), parsed.map { it.text })
    }

    @Test
    fun twoBlankLinesMakeADirectionHeading() {
        val parsed = RecipeListText.parseDirections("Batter\n\nWhisk the dry.\n\n\nSauce\n\nMelt the butter.")
        assertEquals(listOf("Batter", "Whisk the dry.", "Sauce", "Melt the butter."), parsed.map { it.text })
        assertEquals(listOf(false, false, true, false), parsed.map { it.isHeading })
    }

    @Test
    fun aTrailingColonAlsoMakesADirectionHeadingAndEndsTheStepBeforeIt() {
        val parsed = RecipeListText.parseDirections("Sauce:\nMelt the butter.\nStir in cream.")
        assertEquals(listOf("Sauce", "Melt the butter. Stir in cream."), parsed.map { it.text })
        assertEquals(listOf(true, false), parsed.map { it.isHeading })
    }

    @Test
    fun aHeadingEndsTheStepAboveItRatherThanJoiningIt() {
        val parsed = RecipeListText.parseDirections("Whisk the dry.\nSauce:\nMelt the butter.")
        assertEquals(listOf("Whisk the dry.", "Sauce", "Melt the butter."), parsed.map { it.text })
    }

    @Test
    fun trailingBlankLinesDoNotCreateAnEmptyStep() {
        val parsed = RecipeListText.parseDirections("Whisk the dry.\n\n\n\n")
        assertEquals(listOf("Whisk the dry."), parsed.map { it.text })
    }

    @Test
    fun directionsRoundTripThroughText() {
        val original = listOf(
            Direction(id = "a", text = "Whisk the dry."),
            Direction(id = "b", text = "Fold in the wet."),
            Direction(id = "c", isHeading = true, text = "Sauce"),
            Direction(id = "d", text = "Melt the butter."),
        )
        val reparsed = RecipeListText.parseDirections(RecipeListText.formatDirections(original))
        assertEquals(
            original.map { it.text to (it.isHeading == true) },
            reparsed.map { it.text to (it.isHeading == true) },
        )
    }

    @Test
    fun anEmptyBoxClearsTheList() {
        assertEquals(emptyList(), RecipeListText.parseIngredients("   \n\n "))
        assertEquals(emptyList(), RecipeListText.parseDirections("   \n\n "))
    }

    // ---- Clean up ----

    @Test
    fun cleanUpRemovesBulletsAndTrimsEveryLine() {
        val cleaned = RecipeListText.cleanUp("• 2 cups flour\n  -  1 tsp salt \n* 3 eggs", stripNumbering = false)
        assertEquals("2 cups flour\n1 tsp salt\n3 eggs", cleaned)
    }

    @Test
    fun cleanUpStripsStepNumbersOnlyWhenAsked() {
        val text = "1. Whisk the dry.\n2) Fold in the wet."
        assertEquals("Whisk the dry.\nFold in the wet.", RecipeListText.cleanUp(text, stripNumbering = true))
        // Ingredients must keep their leading numbers — that's the quantity, not a list marker.
        assertEquals(
            "2 cups flour\n1 tsp salt",
            RecipeListText.cleanUp("2 cups flour\n1 tsp salt", stripNumbering = false),
        )
    }

    @Test
    fun cleanUpLeavesAnIngredientQuantityAloneEvenWhenNumberingIsStripped() {
        // "2 cups" has no "." or ")" after the digits, so the numbering pattern must not match it.
        assertEquals("2 cups flour", RecipeListText.cleanUp("2 cups flour", stripNumbering = true))
    }

    @Test
    fun parsedRowsGetFreshIds() {
        val parsed = RecipeListText.parseIngredients("2 cups flour\n1 tsp salt")
        assertEquals(2, parsed.map { it.id }.toSet().size)
        assertTrue(parsed.all { it.id.isNotBlank() })
    }
}
