package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.appJson
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Shopping lists — user-scoped, synced as whole rows.
 *
 * `contentsForList` is stored as JSON text (like the recipe list columns) rather than normalized:
 * items never need server-side identity (their ids only matter to client-side merges). See
 * SHOPPING_LIST_REVISIONS_PLAN.md for the sync design.
 *
 * Every write path is optimistic-concurrency-checked against [ShoppingLists.revision]:
 * revision-aware clients send the revision their edit is based on and get [SaveResult.Conflict]
 * (→ 409) on mismatch; legacy clients (no baseRevision) get last-writer-wins, but guarded so a
 * stale write can no longer clobber a newer row.
 */
object ShoppingListRepository {

    sealed interface SaveResult {
        data class Saved(val list: ServerShoppingList) : SaveResult
        data class Conflict(val current: ServerShoppingList) : SaveResult
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data object NotFound : DeleteResult
        data class Conflict(val current: ServerShoppingList) : DeleteResult
    }

    suspend fun list(userId: String): List<ServerShoppingList> = dbQuery {
        ShoppingLists.selectAll().where { ShoppingLists.userId eq userId }
            .orderBy(ShoppingLists.name to SortOrder.ASC)
            .map { it.toDto() }
    }

    suspend fun count(userId: String): Long = dbQuery {
        ShoppingLists.selectAll().where { ShoppingLists.userId eq userId }.count()
    }

    suspend fun getById(userId: String, id: String): ServerShoppingList? = dbQuery {
        ShoppingLists.selectAll()
            .where { (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId) }
            .limit(1).singleOrNull()?.toDto()
    }

    /**
     * Conditional write. Acceptance depends on what the client sent:
     *  - new row → accepted, revision starts at 1;
     *  - `baseRevision` present → accepted only if it equals the stored revision, else [SaveResult.Conflict]
     *    carrying the current row (one round trip gives the client everything it needs to merge);
     *  - no `baseRevision` (legacy client) → last-writer-wins, but a write older than the stored
     *    `lastModifiedDate` is IGNORED and answered with the winning row as [SaveResult.Saved] —
     *    legacy clients abort their entire sync on any non-2xx, so a 409 would brick them.
     */
    suspend fun save(userId: String, incoming: ServerShoppingList): SaveResult = dbQuery {
        // FOR UPDATE serializes racing writers on this row for the rest of the transaction, so
        // check-then-write can't interleave (two concurrent saves resolve to exactly one winner).
        val current = ShoppingLists.selectAll()
            .where { (ShoppingLists.id eq incoming.id) and (ShoppingLists.userId eq userId) }
            .forUpdate().limit(1).singleOrNull()
        val currentRevision = current?.get(ShoppingLists.revision)

        val accepted = when {
            current == null -> true
            incoming.baseRevision != null -> incoming.baseRevision == currentRevision
            else -> {
                val stored = current[ShoppingLists.lastModifiedDate]
                val sent = WireDate.parse(incoming.lastModifiedDate)
                stored == null || (sent != null && !sent.isBefore(stored))
            }
        }
        if (!accepted) {
            val row = current!!.toDto()
            return@dbQuery if (incoming.baseRevision != null) SaveResult.Conflict(row)
            else SaveResult.Saved(row)
        }
        SaveResult.Saved(write(userId, incoming, revision = (currentRevision ?: 0L) + 1L))
    }

    /**
     * Atomic read-modify-write for semantic edits (the web UI's toggle/add/remove item, rename):
     * [transform] sees the CURRENT row and returns the new one, inside the same FOR UPDATE
     * transaction, so these compose with concurrent syncs without needing a baseRevision. Stamps
     * `lastModifiedDate` with server time and bumps the revision. Null when the list doesn't exist.
     */
    suspend fun mutate(
        userId: String,
        id: String,
        transform: (ServerShoppingList) -> ServerShoppingList,
    ): ServerShoppingList? = dbQuery {
        val current = ShoppingLists.selectAll()
            .where { (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId) }
            .forUpdate().limit(1).singleOrNull() ?: return@dbQuery null
        val updated = transform(current.toDto()).copy(
            id = id,
            lastModifiedDate = WireDate.format(WireDate.nowUtc()),
        )
        write(userId, updated, revision = current[ShoppingLists.revision] + 1L)
    }

    /**
     * [expectedRevision] carries a revision-aware client's If-Match: when the stored revision has
     * moved past it, the row changed since that client last looked, and the delete is refused with
     * the current row (edit beats delete — the caller downloads it instead). Null (legacy clients,
     * and web deletes where the user just confirmed) deletes unconditionally, as before.
     */
    suspend fun delete(userId: String, id: String, expectedRevision: Long? = null): DeleteResult = dbQuery {
        if (expectedRevision != null) {
            val current = ShoppingLists.selectAll()
                .where { (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId) }
                .forUpdate().limit(1).singleOrNull() ?: return@dbQuery DeleteResult.NotFound
            if (current[ShoppingLists.revision] != expectedRevision) {
                return@dbQuery DeleteResult.Conflict(current.toDto())
            }
        }
        val deleted = ShoppingLists.deleteWhere {
            (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId)
        } > 0
        if (deleted) DeleteResult.Deleted else DeleteResult.NotFound
    }

    /** The single INSERT-or-UPDATE, with the revision the caller already decided on. */
    private fun write(userId: String, list: ServerShoppingList, revision: Long): ServerShoppingList {
        ShoppingLists.upsert {
            it[id] = list.id
            it[ShoppingLists.userId] = userId
            it[name] = list.name
            it[isFreeform] = list.isFreeform
            // Null contents encode as null rather than "[]", so an older client that omits the field
            // doesn't overwrite a populated list with an empty one.
            it[contentsForList] = list.contentsForList?.let { items ->
                appJson.encodeToString(ListSerializer(ShoppingListListContents.serializer()), items)
            }
            it[contentsForFreeform] = list.contentsForFreeform
            it[lastModifiedDate] = WireDate.parse(list.lastModifiedDate)
            it[ShoppingLists.revision] = revision
        }
        return list.copy(revision = revision, baseRevision = null)
    }

    private fun ResultRow.toDto() = ServerShoppingList(
        id = this[ShoppingLists.id],
        name = this[ShoppingLists.name],
        isFreeform = this[ShoppingLists.isFreeform],
        contentsForList = this[ShoppingLists.contentsForList]
            ?.takeIf { it.isNotBlank() }
            ?.let { appJson.decodeFromString(ListSerializer(ShoppingListListContents.serializer()), it) },
        contentsForFreeform = this[ShoppingLists.contentsForFreeform],
        lastModifiedDate = WireDate.format(this[ShoppingLists.lastModifiedDate]),
        revision = this[ShoppingLists.revision],
    )
}
