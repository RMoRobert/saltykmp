package com.enuvro.saltykmp.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

/**
 * Nothing validates what the server stores, so a client has to survive a bad payload. The rule is
 * narrow on purpose: recover at the ITEM level, fail loudly above it. Mirrors the Swift
 * `ShoppingListSyncTests` malformed-payload cases.
 */
class LenientShoppingListItemsTest {

    private fun decode(json: String) = apiJson.decodeFromString<ServerShoppingList>(json)

    @Test
    fun skipsUnreadableItemsAndKeepsTheRest() {
        // Middle item is missing the non-optional `text`. Without item-level leniency this throws out
        // of fetchShoppingLists() and aborts the entire sync — recipes included — on every attempt.
        val list = decode(
            """{"id":"sl1","contentsForList":[
                 {"id":"a","text":"Milk"},
                 {"id":"b"},
                 {"id":"c","text":"Eggs"}
               ]}"""
        )
        assertEquals(listOf("Milk", "Eggs"), list.contentsForList?.map { it.text })
    }

    @Test
    fun skipsItemsOfTheWrongShapeEntirely() {
        val list = decode("""{"id":"sl1","contentsForList":[{"id":"a","text":"Milk"},"nope",42]}""")
        assertEquals(listOf("Milk"), list.contentsForList?.map { it.text })
    }

    @Test
    fun keepsGoodItemsIntactIncludingNewerFields() {
        val list = decode("""{"id":"sl1","contentsForList":[{"id":"a","text":"Milk","isHeading":true}]}""")
        assertEquals(1, list.contentsForList?.size)
        assertEquals(true, list.contentsForList?.first()?.isHeading)
    }

    @Test
    fun unknownItemFieldsAreIgnoredNotFatal() {
        // A newer peer may send fields this build doesn't model yet.
        val list = decode("""{"id":"sl1","contentsForList":[{"id":"a","text":"Milk","futureField":"x"}]}""")
        assertEquals(listOf("Milk"), list.contentsForList?.map { it.text })
    }

    /** Leniency must not extend to the list itself — absence is how deletions are detected. */
    @Test
    fun aMissingListIdStillThrows() {
        assertFailsWith<SerializationException> { decode("""{"name":"no id here"}""") }
    }
}
