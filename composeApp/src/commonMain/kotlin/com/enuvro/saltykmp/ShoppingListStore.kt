package com.enuvro.saltykmp

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.ShoppingList
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.shopping.ShoppingListText
import com.enuvro.saltykmp.sync.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Reads and user-driven writes for shopping lists.
 *
 * Every write here is a LOCAL EDIT and must go through [LocalStore.updateLocalShoppingList] /
 * [LocalStore.insertLocalShoppingList] — never `upsertShoppingList`, which is reserved for rows the server
 * has agreed to and would wipe the baseline that revision-based conflict detection compares against
 * (see SHOPPING_LIST_REVISIONS_PLAN.md). Each write stamps a fresh `lastModifiedDate`, which is exactly
 * what marks the row dirty for the next sync.
 */
class ShoppingListStore(db: AppDatabase, private val local: LocalStore) {
    private val q = db.queriesQueries

    fun lists(): Flow<List<ShoppingList>> =
        q.selectAllShoppingLists().asFlow().mapToList(Dispatchers.Default)

    fun list(id: String): ShoppingList? =
        q.selectAllShoppingLists().executeAsList().firstOrNull { it.id == id }

    /** Create an empty list of the chosen kind and return its id. */
    fun create(name: String, isFreeform: Boolean): String {
        val id = newId()
        local.insertLocalShoppingList(
            ServerShoppingList(
                id = id,
                name = name,
                isFreeform = isFreeform,
                contentsForList = emptyList(),
                contentsForFreeform = if (isFreeform) "" else null,
                lastModifiedDate = nowTimestamp(),
            ),
        )
        return id
    }

    fun rename(row: ShoppingList, name: String) = save(row) { it.copy(name = name) }

    fun setItems(row: ShoppingList, items: List<ShoppingListListContents>) =
        save(row) { it.copy(contentsForList = items) }

    fun setFreeformText(row: ShoppingList, text: String) =
        save(row) { it.copy(contentsForFreeform = text) }

    /**
     * "Convert to Freeform Text": serialize the checklist into the freeform field and flip the kind. The
     * items are left in place so the conversion is recoverable by flipping back (and so a checklist synced
     * from another device isn't destroyed by a stray tap here).
     */
    fun convertToFreeform(row: ShoppingList) = save(row) {
        it.copy(
            isFreeform = true,
            contentsForFreeform = ShoppingListText.toFreeformText(row.contentsForList.orEmpty()),
        )
    }

    /** The inverse: parse the freeform text into checklist items and flip the kind back. */
    fun convertToChecklist(row: ShoppingList) = save(row) {
        it.copy(
            isFreeform = false,
            contentsForList = ShoppingListText.toItems(row.contentsForFreeform.orEmpty()),
        )
    }

    fun delete(id: String) = local.deleteShoppingList(id)

    /** Apply [edit] to the row's wire form and persist it with a fresh modification stamp. */
    private fun save(row: ShoppingList, edit: (ServerShoppingList) -> ServerShoppingList) {
        val updated = edit(row.toWire()).copy(lastModifiedDate = nowTimestamp())
        local.updateLocalShoppingList(updated)
    }

    private fun ShoppingList.toWire() = ServerShoppingList(
        id = id,
        name = name,
        isFreeform = isFreeform,
        contentsForList = contentsForList ?: emptyList(),
        contentsForFreeform = contentsForFreeform,
        lastModifiedDate = LocalStore.dbToWireDate(lastModifiedDate),
    )
}
