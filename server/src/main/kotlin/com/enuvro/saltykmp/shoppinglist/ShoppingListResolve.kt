package com.enuvro.saltykmp.shoppinglist

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.ShoppingListRepository
import com.enuvro.saltykmp.sync.ShoppingListMerge
import com.enuvro.saltykmp.util.WireDate
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Server-side conflict resolution for thin clients.
 *
 * Native clients resolve conflicts themselves: they keep a `syncedSnapshot` of the last agreed
 * state, so on a 409 they have all three sides and run [ShoppingListMerge] locally. The browser
 * has no local database and no snapshot, and porting a data-loss-critical merge into JavaScript
 * would make a fourth copy of it — exactly what the shared contract corpus exists to prevent.
 *
 * So the browser sends the two sides it does have — the copy it loaded (`base`) and its edited
 * version (`local`) — and the server supplies the third from storage and runs the same shared
 * merge the native clients use. Nothing new is invented here; this is a transport for existing
 * logic.
 *
 * `base` may be null (a client that never held a clean copy), which [ShoppingListMerge] handles
 * as a documented two-way degrade: per-item unions, and completed/important still OR together so
 * a check-off is never lost.
 */
@Serializable
data class ResolveRequest(
    /** The clean copy the client started from, if it has one. Null degrades to a two-way merge. */
    val base: ServerShoppingList? = null,
    /** The client's edited version. */
    val local: ServerShoppingList,
)

@Serializable
data class ResolveResponse(
    /** The stored, merged list. The client should replace its copy with this wholesale. */
    val merged: ServerShoppingList,
    /**
     * Set when a side could not be merged — freeform text changed on both sides, or the two
     * disagree on isFreeform. That side is preserved verbatim as a brand-new list rather than
     * being discarded, and has already been saved.
     */
    val conflictCopy: ServerShoppingList? = null,
)

object ShoppingListResolver {

    sealed interface Result {
        data class Resolved(val response: ResolveResponse) : Result
        /** The row vanished between the client's read and this call. */
        data object NotFound : Result
        /** Someone wrote again while we were merging; the caller should retry with fresh input. */
        data class Raced(val current: ServerShoppingList) : Result
    }

    /**
     * Merges [request] against the stored row and persists the result.
     *
     * The write is itself optimistic: it goes in with the revision the merge was computed from, so
     * a third party writing in the meantime produces [Result.Raced] rather than clobbering them.
     */
    suspend fun resolve(
        userId: String,
        id: String,
        request: ResolveRequest,
        newId: () -> String,
        today: () -> LocalDate = { LocalDate.now(java.time.ZoneOffset.UTC) },
    ): Result {
        val current = ShoppingListRepository.getById(userId, id) ?: return Result.NotFound

        val resolution = ShoppingListMerge.resolve(
            base = request.base,
            local = request.local.copy(id = id),
            server = current,
            conflictCopyId = newId(),
            conflictCopyLabel = "conflicted copy ${today()}",
        )

        // Stamp the merge as based on the revision we just read, so a concurrent write loses here
        // rather than being silently overwritten.
        val toSave = resolution.merged.copy(
            id = id,
            baseRevision = current.revision,
            // nowUtc(), not LocalDateTime.now(): WireDate.FORMAT appends a literal 'Z' without converting,
            // so a local-zone value would be stored as UTC and could sit hours in the future --
            // enough for ShoppingListRepository's legacy path to swallow a client's genuine write.
            lastModifiedDate = resolution.merged.lastModifiedDate ?: WireDate.format(WireDate.nowUtc()),
        )

        val saved = when (val r = ShoppingListRepository.save(userId, toSave)) {
            is ShoppingListRepository.SaveResult.Saved -> r.list
            is ShoppingListRepository.SaveResult.Conflict -> return Result.Raced(r.current)
        }

        // The conflict copy is a brand-new list, so it has no revision to conflict with.
        val savedCopy = resolution.conflictCopy?.let { copy ->
            when (val r = ShoppingListRepository.save(userId, copy)) {
                is ShoppingListRepository.SaveResult.Saved -> r.list
                is ShoppingListRepository.SaveResult.Conflict -> null
            }
        }

        return Result.Resolved(ResolveResponse(merged = saved, conflictCopy = savedCopy))
    }
}
