package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.util.nowWireIso

/*
 * Courses, categories and tags deleted HERE, recorded so the next sync deletes them on the server as a
 * fact rather than inferring it. Port of Salty's `ClassifierTombstoneWriter`; same tables, same shape as
 * `deletedRecipe` (`deletedCourse`, `deletedCategory`, `deletedTag`).
 *
 * What it replaces: a classifier on the server and not here used to be judged by the watermark, and one
 * stamped before this device's last sync read as "deleted here" and was DELETED on the server — wrong
 * whenever the row is one this device never had (a restored backup, another app's library, a row
 * another client uploaded with its original date). Each of those removed the user's data everywhere.
 * With tombstones, only a recorded deletion deletes on the server; any other server-only row is
 * downloaded (SyncReconciler.planClassifiers).
 *
 * Record one only for a deletion made here on purpose — the editor, a merge — inside the same
 * transaction as the delete. Never for a deletion sync applies from the server: that would echo it back.
 */

/** Records [ids] of [kind] as deleted on this device. Call inside the deleting transaction. */
internal fun QueriesQueries.recordClassifierTombstones(
    kind: LibraryClassifier,
    ids: Collection<String>,
    deletedDate: String = nowWireIso(),
) {
    ids.filter { it.isNotBlank() }.forEach { id ->
        when (kind) {
            LibraryClassifier.COURSE -> insertDeletedCourse(id, deletedDate)
            LibraryClassifier.CATEGORY -> insertDeletedCategory(id, deletedDate)
            LibraryClassifier.TAG -> insertDeletedTag(id, deletedDate)
        }
    }
}

/** Ids of [kind] deleted here and not yet dealt with by a sync. */
internal fun QueriesQueries.classifierTombstones(kind: LibraryClassifier): Set<String> =
    when (kind) {
        LibraryClassifier.COURSE -> selectDeletedCourseIds()
        LibraryClassifier.CATEGORY -> selectDeletedCategoryIds()
        LibraryClassifier.TAG -> selectDeletedTagIds()
    }.executeAsList().toSet()

/** Forgets tombstones a sync has dealt with: deleted on the server, overruled by an edit, or stale. */
internal fun QueriesQueries.clearClassifierTombstones(kind: LibraryClassifier, ids: Collection<String>) {
    ids.forEach { id ->
        when (kind) {
            LibraryClassifier.COURSE -> deleteCourseTombstone(id)
            LibraryClassifier.CATEGORY -> deleteCategoryTombstone(id)
            LibraryClassifier.TAG -> deleteTagTombstone(id)
        }
    }
}

/** Forgets every pending classifier deletion — for the force re-syncs. */
internal fun QueriesQueries.clearAllClassifierTombstones() {
    deleteAllCourseTombstones()
    deleteAllCategoryTombstones()
    deleteAllTagTombstones()
}
