package com.enuvro.saltykmp

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where [IngredientQuantity][com.enuvro.saltykmp.text.IngredientQuantity] decides *what* the quantity
 * is, this decides what is drawn bold — and the two are not the same claim. A bullet or a heading
 * inside the emphasised run would be a bug this parser could never see.
 */
class IngredientLineTest {

    @Test
    fun theQuantityAndItsUnitAreBoldAndTheBulletIsNot() {
        val line = ingredientLine("1 c flour", prefix = "• ")
        assertEquals("• 1 c flour", line.text)

        val span = line.spanStyles.single()
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals("1 c", line.text.substring(span.start, span.end), "the unit is bold with its number")
    }

    @Test
    fun aCountWithNoUnitEmphasisesOnlyTheNumber() {
        val line = ingredientLine("2 onions, peeled and diced")
        assertEquals("2 onions, peeled and diced", line.text)

        val span = line.spanStyles.single()
        assertEquals("2", line.text.substring(span.start, span.end), "\"onions\" is not a unit")
    }

    @Test
    fun aLineWithNoQuantityIsDrawnPlain() {
        val line = ingredientLine("pinch of salt", prefix = "• ")
        assertEquals("• pinch of salt", line.text)
        assertTrue(line.spanStyles.isEmpty(), "nothing to emphasise means no styled run at all")
    }

    /** An ingredient that is nothing but a quantity has no trailing space left where the rest would be. */
    @Test
    fun aQuantityWithNothingAfterItLeavesNoTrailingSpace() {
        assertEquals("2 cups", ingredientLine("2 cups").text)
    }
}
