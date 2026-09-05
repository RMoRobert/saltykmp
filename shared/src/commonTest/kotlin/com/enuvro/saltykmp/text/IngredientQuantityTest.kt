package com.enuvro.saltykmp.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases here are the Swift app's own (`IngredientParseQuantityTests`, `IngredientScalerTests`),
 * because the point of this parser is that it agrees with that one — a recipe that reads "1 c flour"
 * on the Mac must not read "1 c. flour" with the "c." in the wrong half here.
 */
class IngredientQuantityTest {

    private fun split(text: String) = IngredientQuantity.split(text)

    @Test
    fun takesTheUnitIntoTheQuantity() {
        assertEquals(IngredientQuantity.Parts("2 cups", "flour"), split("2 cups flour"))
        assertEquals(IngredientQuantity.Parts("1 c", "flour"), split("1 c flour"))
        assertEquals(IngredientQuantity.Parts("1/2 tsp", "salt"), split("1/2 tsp salt"))
        assertEquals(IngredientQuantity.Parts("1 1/2 cups", "flour"), split("1 1/2 cups flour"))
    }

    /** An abbreviation with a full stop is a unit too, and it keeps the stop. */
    @Test
    fun takesAnAbbreviatedUnitWithItsPeriod() {
        assertEquals(IngredientQuantity.Parts("2 c.", "milk"), split("2 c. milk"))
    }

    @Test
    fun aCountWithNoUnitKeepsTheWholeIngredient() {
        assertEquals(IngredientQuantity.Parts("3", "eggs"), split("3 eggs"))
        assertEquals(
            IngredientQuantity.Parts("2", "onions, peeled and diced"),
            split("2 onions, peeled and diced"),
        )
    }

    @Test
    fun aRangeIsOneQuantityAndItsHyphenIsNormalised() {
        assertEquals(IngredientQuantity.Parts("2-3 tbsp", "oil"), split("2-3 tbsp oil"))
        assertEquals(IngredientQuantity.Parts("2-3 tbsp", "oil"), split("2 - 3 tbsp oil"))
    }

    /**
     * Multi-word units are matched longest first. Listed the other way round — which is how the Swift
     * list read until September 2026 — this split as "2 fluid ounce" and "s water".
     */
    @Test
    fun aPluralMultiWordUnitIsTakenWhole() {
        assertEquals(IngredientQuantity.Parts("2 fluid ounces", "water"), split("2 fluid ounces water"))
        assertEquals(IngredientQuantity.Parts("1 fluid ounce", "bourbon"), split("1 fluid ounce bourbon"))
        assertEquals(IngredientQuantity.Parts("8 fl oz", "milk"), split("8 fl oz milk"))
        assertEquals(IngredientQuantity.Parts("8 fl. oz.", "milk"), split("8 fl. oz. milk"))
    }

    /** The unit is echoed back as the author capitalised it, not as the set spells it. */
    @Test
    fun theUnitKeepsTheAuthorsCapitalisation() {
        assertEquals(IngredientQuantity.Parts("8 Fl Oz", "milk"), split("8 Fl Oz milk"))
        assertEquals(IngredientQuantity.Parts("2 Cups", "flour"), split("2 Cups flour"))
    }

    @Test
    fun aLineWithNoLeadingNumberHasNoQuantity() {
        assertEquals(IngredientQuantity.Parts("", "salt, to taste"), split("salt, to taste"))
        assertEquals(IngredientQuantity.Parts("", "pinch salt"), split("pinch salt"))
        assertEquals(IngredientQuantity.Parts("", ""), split("   "))
    }

    @Test
    fun aNumberOnItsOwnIsAllQuantity() {
        assertEquals(IngredientQuantity.Parts("2", ""), split("2"))
    }
}
