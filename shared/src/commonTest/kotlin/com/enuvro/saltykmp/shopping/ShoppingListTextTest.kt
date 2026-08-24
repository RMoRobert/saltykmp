package com.enuvro.saltykmp.shopping

import com.enuvro.saltykmp.db.model.ShoppingListListContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingListTextTest {

    /** Deterministic ids so parse results can be compared directly. */
    private fun ids(): () -> String {
        var n = 0
        return { "id${++n}" }
    }

    private fun parse(text: String) = ShoppingListText.toItems(text, ids())

    @Test fun parsesHeadingsBulletsAndCheckboxes() {
        val items = parse(
            """
            # Produce
            * [ ] Apples
            * [x] Bananas
            - Carrots
            """.trimIndent(),
        )
        assertEquals(4, items.size)
        assertEquals(ShoppingListListContents("id1", isHeading = true, text = "Produce"), items[0])
        assertEquals("Apples", items[1].text)
        assertEquals(false, items[1].isCompleted)
        assertEquals("Bananas", items[2].text)
        assertEquals(true, items[2].isCompleted)
        // A bare bullet with no checkbox is an incomplete item.
        assertEquals("Carrots", items[3].text)
        assertEquals(false, items[3].isCompleted)
    }

    @Test fun acceptsEveryBulletCharacterAndUppercaseX() {
        val items = parse("* a\n- b\n+ c\n• d\n* [X] e")
        assertEquals(listOf("a", "b", "c", "d", "e"), items.map { it.text })
        assertEquals(true, items.last().isCompleted)
    }

    @Test fun plainLinesBecomeItems() {
        val items = parse("Milk\nEggs")
        assertEquals(listOf("Milk", "Eggs"), items.map { it.text })
        assertTrue(items.none { it.isHeading == true })
    }

    @Test fun dropsBlankAndEmptyLines() {
        // Blank lines, a bullet with no text, and a heading with no title all vanish.
        val items = parse("# Produce\n\n   \n*   \n#\nApples")
        assertEquals(listOf("Produce", "Apples"), items.map { it.text })
    }

    @Test fun multipleHashesStillMakeOneHeading() {
        val items = parse("## Dairy")
        assertEquals(1, items.size)
        assertEquals(true, items[0].isHeading)
        assertEquals("Dairy", items[0].text)
    }

    @Test fun serializesToTheGrammarItParses() {
        val items = listOf(
            ShoppingListListContents("a", isHeading = true, text = "Produce"),
            ShoppingListListContents("b", isCompleted = false, text = "Apples"),
            ShoppingListListContents("c", isCompleted = true, text = "Bananas"),
        )
        assertEquals("# Produce\n* [ ] Apples\n* [x] Bananas", ShoppingListText.toFreeformText(items))
    }

    @Test fun roundTripsThroughTextPreservingTextHeadingAndCompletion() {
        val original = listOf(
            ShoppingListListContents("a", isHeading = true, text = "Produce"),
            ShoppingListListContents("b", isCompleted = true, text = "Apples"),
            ShoppingListListContents("c", isCompleted = false, text = "Pears"),
        )
        val back = ShoppingListText.toItems(ShoppingListText.toFreeformText(original), ids())
        assertEquals(original.map { Triple(it.text, it.isHeading == true, it.isCompleted == true) },
            back.map { Triple(it.text, it.isHeading == true, it.isCompleted == true) })
    }

    @Test fun emptyTextParsesToNoItems() {
        assertEquals(emptyList(), parse(""))
        assertEquals("", ShoppingListText.toFreeformText(emptyList()))
    }

    @Test fun summaryCountsCheckListItemsExcludingHeadings() {
        val items = listOf(
            ShoppingListListContents("a", isHeading = true, text = "Produce"),
            ShoppingListListContents("b", text = "Apples"),
        )
        assertEquals("1 item", ShoppingListText.contentsSummary(false, items, null))
        assertEquals("2 items", ShoppingListText.contentsSummary(false, items + ShoppingListListContents("c", text = "Pears"), null))
        assertEquals("No items", ShoppingListText.contentsSummary(false, emptyList(), null))
        // Headings alone are not things to buy.
        assertEquals("No items", ShoppingListText.contentsSummary(false, items.take(1), null))
    }

    @Test fun summaryCountsFreeformNonBlankLines() {
        assertEquals("2 lines", ShoppingListText.contentsSummary(true, emptyList(), "Milk\n\nEggs"))
        assertEquals("1 line", ShoppingListText.contentsSummary(true, emptyList(), "Milk"))
        assertEquals("Empty", ShoppingListText.contentsSummary(true, emptyList(), "\n   \n"))
        assertEquals("Empty", ShoppingListText.contentsSummary(true, emptyList(), null))
    }
}
