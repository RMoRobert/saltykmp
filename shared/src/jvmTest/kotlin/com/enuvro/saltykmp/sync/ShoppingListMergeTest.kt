package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The merge is data-loss-critical, so these enumerate every rule from the KDoc. The Swift port
 * (ShoppingListMergeTests.swift) mirrors these vectors 1:1 — change them together.
 */
class ShoppingListMergeTest {

    private val t1 = "2026-08-01T00:00:00.000Z"
    private val t2 = "2026-08-02T00:00:00.000Z"
    private val t3 = "2026-08-03T00:00:00.000Z"

    private fun item(id: String, text: String, done: Boolean = false, heading: Boolean = false, important: Boolean = false) =
        ShoppingListListContents(id = id, text = text, isCompleted = done, isHeading = heading, isImportant = important)

    private fun checklist(vararg items: ShoppingListListContents, name: String = "Groceries", date: String = t1) =
        ServerShoppingList(id = "L", name = name, isFreeform = false, contentsForList = items.toList(), lastModifiedDate = date)

    private fun freeform(text: String, name: String = "Notes", date: String = t1) =
        ServerShoppingList(id = "L", name = name, isFreeform = true, contentsForFreeform = text, lastModifiedDate = date)

    private fun resolve(base: ServerShoppingList?, local: ServerShoppingList, server: ServerShoppingList) =
        ShoppingListMerge.resolve(base, local, server, conflictCopyId = "COPY", conflictCopyLabel = "conflicted copy")

    private fun texts(l: ServerShoppingList?) = l?.contentsForList?.map { it.text }

    // ---- checklist: different items ----

    @Test
    fun editsToDifferentItemsMergeSilently() {
        val base = checklist(item("a", "Milk"), item("b", "Eggs"))
        val local = checklist(item("a", "Milk"), item("b", "Eggs"), item("c", "Bread"), date = t2)   // added c
        val server = checklist(item("a", "Milk", done = true), item("b", "Eggs"), date = t3)          // checked a
        val r = resolve(base, local, server)
        assertNull(r.conflictCopy)
        assertEquals(listOf("Milk", "Eggs", "Bread"), texts(r.merged))
        assertEquals(true, r.merged.contentsForList?.first()?.isCompleted, "server's check-off survives")
        assertEquals(t3, r.merged.lastModifiedDate, "merged carries the newer date")
    }

    @Test
    fun sameItemCheckAndTextEditBothSurvive() {
        val base = checklist(item("a", "Milk"))
        val local = checklist(item("a", "Milk", done = true), date = t2)          // checked it
        val server = checklist(item("a", "Whole Milk"), date = t3)                // renamed it
        val merged = resolve(base, local, server).merged.contentsForList!!.single()
        assertEquals("Whole Milk", merged.text)
        assertEquals(true, merged.isCompleted)
    }

    // ---- checklist: deletions ----

    @Test
    fun deleteOfAnUnchangedItemStands() {
        val base = checklist(item("a", "Milk"), item("b", "Eggs"))
        val local = checklist(item("a", "Milk"), date = t2)                       // deleted b (unchanged)
        val server = checklist(item("a", "Milk"), item("b", "Eggs"), date = t1)
        assertEquals(listOf("Milk"), texts(resolve(base, local, server).merged))
    }

    @Test
    fun editBeatsDeleteInBothDirections() {
        val base = checklist(item("a", "Milk"), item("b", "Eggs"))
        // Local deleted b; server edited b since base → b survives with the server's edit.
        val localDeleted = checklist(item("a", "Milk"), date = t2)
        val serverEdited = checklist(item("a", "Milk"), item("b", "Duck Eggs"), date = t3)
        assertEquals(listOf("Milk", "Duck Eggs"), texts(resolve(base, localDeleted, serverEdited).merged))
        // Mirror image: server deleted b; local edited it.
        val localEdited = checklist(item("a", "Milk"), item("b", "Duck Eggs"), date = t3)
        val serverDeleted = checklist(item("a", "Milk"), date = t2)
        assertEquals(listOf("Milk", "Duck Eggs"), texts(resolve(base, localEdited, serverDeleted).merged))
    }

    // ---- checklist: ordering ----

    @Test
    fun localAdditionsSlotInAfterTheirSurvivingPredecessor() {
        val base = checklist(item("a", "Milk"), item("b", "Eggs"))
        // Local inserted x after a, and y after b; server appended z.
        val local = checklist(item("a", "Milk"), item("x", "Butter"), item("b", "Eggs"), item("y", "Jam"), date = t2)
        val server = checklist(item("a", "Milk"), item("b", "Eggs"), item("z", "Tea"), date = t3)
        assertEquals(listOf("Milk", "Butter", "Eggs", "Jam", "Tea"), texts(resolve(base, local, server).merged))
    }

    @Test
    fun localAdditionAtTheFrontLandsAtTheFront() {
        val base = checklist(item("a", "Milk"))
        val local = checklist(item("x", "Butter"), item("a", "Milk"), date = t2)
        val server = checklist(item("a", "Milk"), date = t1)
        assertEquals(listOf("Butter", "Milk"), texts(resolve(base, local, server).merged))
    }

    // ---- no base (legacy rows): two-way union ----

    @Test
    fun noBaseUnionsAdditionsAndOrsFlags() {
        val local = checklist(item("a", "Milk", done = true), item("c", "Bread"), date = t2)
        val server = checklist(item("a", "Milk"), item("b", "Eggs"), date = t3)
        val r = resolve(null, local, server)
        assertNull(r.conflictCopy)
        assertEquals(listOf("Milk", "Bread", "Eggs"), texts(r.merged))
        assertEquals(true, r.merged.contentsForList?.first()?.isCompleted, "no-base flag disagreement ORs: checked wins")
    }

    // ---- scalars ----

    @Test
    fun nameChangedOnOneSideWinsWithoutTouchingTheOtherSidesItems() {
        val base = checklist(item("a", "Milk"), name = "Groceries")
        val local = checklist(item("a", "Milk"), name = "Weekly Shop", date = t2)              // renamed
        val server = checklist(item("a", "Milk", done = true), name = "Groceries", date = t3)  // checked
        val r = resolve(base, local, server).merged
        assertEquals("Weekly Shop", r.name)
        assertEquals(true, r.contentsForList?.single()?.isCompleted)
    }

    @Test
    fun nameChangedOnBothSidesFallsBackToNewer() {
        val base = checklist(item("a", "Milk"), name = "Groceries")
        val local = checklist(item("a", "Milk"), name = "Local Name", date = t3)
        val server = checklist(item("a", "Milk"), name = "Server Name", date = t2)
        assertEquals("Local Name", resolve(base, local, server).merged.name)
    }

    // ---- freeform ----

    @Test
    fun freeformChangedOnOneSideMergesClean() {
        val base = freeform("v0")
        val local = freeform("v0", date = t1)
        val server = freeform("v2", date = t3)
        val r = resolve(base, local, server)
        assertNull(r.conflictCopy)
        assertEquals("v2", r.merged.contentsForFreeform)
    }

    @Test
    fun freeformChangedOnBothSidesPreservesLocalAsConflictCopy() {
        val base = freeform("v0")
        val local = freeform("local words", date = t3)
        val server = freeform("server words", date = t2)
        val r = resolve(base, local, server)
        assertEquals("server words", r.merged.contentsForFreeform, "server text stays on the shared list")
        val copy = assertNotNull(r.conflictCopy, "local text must survive as a new list")
        assertEquals("COPY", copy.id)
        assertEquals("local words", copy.contentsForFreeform)
        assertTrue(copy.name!!.contains("conflicted copy"), "copy is visibly labeled: was '${copy.name}'")
    }

    @Test
    fun isFreeformFlipIsAWholeListConflict() {
        val base = checklist(item("a", "Milk"))
        val local = freeform("# now freeform", date = t3)                          // converted (newer)
        val server = checklist(item("a", "Milk", done = true), date = t2)          // kept checking items
        val r = resolve(base, local, server)
        assertEquals(true, r.merged.isFreeform, "newer side wins the shape")
        assertEquals("# now freeform", r.merged.contentsForFreeform)
        val copy = assertNotNull(r.conflictCopy, "the older side survives whole")
        assertEquals(false, copy.isFreeform)
        assertEquals(listOf("Milk"), texts(copy))
    }

    // ---- ids/revisions on outputs ----

    @Test
    fun mergedAndCopyNeverCarryRevisions() {
        val base = freeform("v0")
        val r = resolve(
            base,
            freeform("a", date = t2).copy(revision = 7, baseRevision = 7),
            freeform("b", date = t3).copy(revision = 9),
        )
        assertNull(r.merged.revision); assertNull(r.merged.baseRevision)
        assertNull(r.conflictCopy?.revision); assertNull(r.conflictCopy?.baseRevision)
    }
}
