package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.model.ShoppingListListContents

/**
 * Pure three-way merge for a shopping list that changed on BOTH sides since the last server
 * agreement ([base], the stored `syncedSnapshot`). Mirrors [SyncReconciler]'s design contract: pure,
 * I/O-free, data-loss-critical, exhaustively unit-tested, ported line-for-line to the Swift app
 * (ShoppingListMerge.swift) — keep the two in lockstep.
 *
 * Resolution rules:
 *  - Scalars (name, isFreeform): the side that changed vs base wins; both changed → newer
 *    `lastModifiedDate` wins (clocks are only ever a tie-breaker, never the primary signal).
 *  - Checklist items, keyed by their stable item ids: adds/edits/deletes on DIFFERENT items merge
 *    silently; same-item edits merge field-level (text: newer side; completed/important: OR — a
 *    checked-off or flagged item stays that way); a delete loses to an edit of the same item.
 *    Item order: the server's order is the spine, local-only additions slot in after their nearest
 *    surviving local predecessor.
 *  - Freeform text (one opaque markdown blob) changed on both sides, or the two sides disagree on
 *    isFreeform itself: no sensible auto-merge exists, so the newer side becomes [Resolution.merged]
 *    and the other side is preserved WHOLE as [Resolution.conflictCopy] — a brand-new list the
 *    caller inserts and uploads. Nothing is ever silently discarded.
 *  - No base (legacy row, or an unreadable snapshot): two-way merge — same shape, but "changed vs
 *    base" degrades to newer-wins per field, and item unions keep both sides' additions.
 *
 * The merged list's `lastModifiedDate` is the newer of the two inputs; `revision`/`baseRevision`
 * are cleared — the caller stamps `baseRevision` when uploading.
 */
object ShoppingListMerge {

    data class Resolution(
        val merged: ServerShoppingList,
        /** Set only when a side couldn't be merged in (freeform/isFreeform conflicts): a NEW list
         *  (fresh [conflictCopyId]) preserving that side verbatim. Null = clean merge. */
        val conflictCopy: ServerShoppingList? = null,
    )

    /**
     * @param conflictCopyId fresh id to use IF a conflict copy is needed (pure function — the
     *   caller owns id generation).
     * @param conflictCopyLabel appended to the copy's name, e.g. "conflicted copy 2026-08-13".
     */
    fun resolve(
        base: ServerShoppingList?,
        local: ServerShoppingList,
        server: ServerShoppingList,
        conflictCopyId: String,
        conflictCopyLabel: String,
    ): Resolution {
        val localIsNewer = LocalStore.parseOrPast(local.lastModifiedDate) > LocalStore.parseOrPast(server.lastModifiedDate)
        val newer = if (localIsNewer) local else server
        val older = if (localIsNewer) server else local
        val mergedDate = newer.lastModifiedDate

        fun conflictCopyOf(side: ServerShoppingList) = side.copy(
            id = conflictCopyId,
            name = "${side.name ?: "Shopping List"} ($conflictCopyLabel)",
            revision = null,
            baseRevision = null,
        )

        // The two sides disagree on what KIND of list this is (one converted it to freeform):
        // structurally unmergeable — newer side wins, older side survives as a copy.
        val isFreeform = pick(base?.isFreeform.eff(), local.isFreeform.eff(), server.isFreeform.eff(), localIsNewer)
        if (local.isFreeform.eff() != server.isFreeform.eff()) {
            return Resolution(
                merged = newer.copy(revision = null, baseRevision = null),
                conflictCopy = conflictCopyOf(older),
            )
        }

        val name = pick(base?.name, local.name, server.name, localIsNewer)

        if (isFreeform) {
            val baseText = base?.contentsForFreeform
            val localText = local.contentsForFreeform
            val serverText = server.contentsForFreeform
            val bothChanged = base != null &&
                localText != serverText && localText != baseText && serverText != baseText
            val bothChangedNoBase = base == null && localText != serverText
            if (bothChanged || bothChangedNoBase) {
                return Resolution(
                    merged = server.copy(name = name, lastModifiedDate = mergedDate, revision = null, baseRevision = null),
                    conflictCopy = conflictCopyOf(local),
                )
            }
            return Resolution(
                merged = ServerShoppingList(
                    id = local.id,
                    name = name,
                    isFreeform = true,
                    contentsForList = null,
                    contentsForFreeform = pick(baseText, localText, serverText, localIsNewer),
                    lastModifiedDate = mergedDate,
                ),
            )
        }

        return Resolution(
            merged = ServerShoppingList(
                id = local.id,
                name = name,
                isFreeform = false,
                contentsForList = mergeItems(
                    base?.contentsForList,
                    local.contentsForList.orEmpty(),
                    server.contentsForList.orEmpty(),
                    localIsNewer,
                ),
                contentsForFreeform = pick(base?.contentsForFreeform, local.contentsForFreeform, server.contentsForFreeform, localIsNewer),
                lastModifiedDate = mergedDate,
            ),
        )
    }

    /** Changed-side-wins for one scalar; both changed (or no base to tell) → newer side. */
    private fun <T> pick(baseValue: T?, localValue: T, serverValue: T, localIsNewer: Boolean): T = when {
        localValue == serverValue -> localValue
        baseValue == null -> if (localIsNewer) localValue else serverValue
        localValue == baseValue -> serverValue
        serverValue == baseValue -> localValue
        else -> if (localIsNewer) localValue else serverValue
    }

    private fun mergeItems(
        base: List<ShoppingListListContents>?,
        local: List<ShoppingListListContents>,
        server: List<ShoppingListListContents>,
        localIsNewer: Boolean,
    ): List<ShoppingListListContents> {
        val baseById = base.orEmpty().associateBy { it.id }
        val localById = local.associateBy { it.id }
        val serverById = server.associateBy { it.id }
        val hasBase = base != null

        // Server order is the spine.
        val result = mutableListOf<ShoppingListListContents>()
        for (s in server) {
            val l = localById[s.id]
            val b = baseById[s.id]
            when {
                l != null -> result += mergeItem(b, l, s, localIsNewer)
                !hasBase || b == null -> result += s          // new on the server (or no base to judge)
                s != b -> result += s                          // local deleted it, but the server edited it since base → edit beats delete
                // else: local deleted an unchanged item → deletion stands
            }
        }

        // Local-only items slot in after their nearest local predecessor that survived the merge.
        for ((index, l) in local.withIndex()) {
            if (l.id in serverById) continue
            val b = baseById[l.id]
            val keep = !hasBase || b == null || l != b        // new locally, or edited since base (edit beats delete)
            if (!keep) continue
            var insertAt = 0
            for (j in index - 1 downTo 0) {
                val anchor = result.indexOfFirst { it.id == local[j].id }
                if (anchor >= 0) {
                    insertAt = anchor + 1
                    break
                }
            }
            result.add(insertAt, l)
        }
        return result
    }

    /** Same item touched on both sides: field-level, so a check-off and a text edit both survive. */
    private fun mergeItem(
        base: ShoppingListListContents?,
        local: ShoppingListListContents,
        server: ShoppingListListContents,
        localIsNewer: Boolean,
    ): ShoppingListListContents {
        if (local == server) return local
        return ShoppingListListContents(
            id = local.id,
            text = pick(base?.text, local.text, server.text, localIsNewer),
            isCompleted = mergeFlag(base?.isCompleted, local.isCompleted, server.isCompleted),
            isImportant = mergeFlag(base?.isImportant, local.isImportant, server.isImportant),
            isHeading = pick(base?.isHeading.eff(), local.isHeading.eff(), server.isHeading.eff(), localIsNewer),
        )
    }

    /**
     * Boolean flags normalize null==false (the DTO defaults). With a base, changed-side-wins; both
     * changed toward different values is impossible for booleans, so the no-base disagreement case
     * resolves by OR — "someone checked it off / flagged it" always survives the merge.
     */
    private fun mergeFlag(baseValue: Boolean?, localValue: Boolean?, serverValue: Boolean?): Boolean {
        val b = baseValue.eff()
        val l = localValue.eff()
        val s = serverValue.eff()
        return when {
            l == s -> l
            baseValue == null -> l || s
            l != b && s == b -> l
            s != b && l == b -> s
            else -> l || s
        }
    }

    private fun Boolean?.eff(): Boolean = this == true
}
