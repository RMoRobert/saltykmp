package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.util.newId

/**
 * "The course/category/tag called X, creating it if it doesn't exist yet" — port of Salty's
 * `LibraryClassifierResolver`, for importers, which carry classifiers by NAME (ids mean nothing in
 * another library).
 *
 * Matching is [LibraryDuplicateFinder.normalizedName]'s: case, surrounding whitespace and runs of
 * internal whitespace are ignored, accents are not. It has to be the finder's rule and no other — an
 * import that matched more strictly would create exactly the rows the post-sync duplicate fold then
 * merges away, and one that matched more loosely would file recipes under rows that fold never joins.
 */
class LibraryClassifierResolver(private val db: AppDatabase) {

    private val q get() = db.queriesQueries

    /**
     * The id of the row of [kind] named [name], creating that row if nothing matches. A name that is
     * empty once trimmed returns null: an unnamed classifier isn't worth creating, and callers treat
     * null as "not filed under anything".
     *
     * When several rows already match (duplicates the fold hasn't reached yet), the smallest id wins —
     * the fold's own survivor rule, so the recipe lands on the row that will still exist afterwards.
     *
     * A new row keeps the trimmed name as given, capitalization included, and is stamped [now] in the
     * database's date format. Its agreement stamp stays null, so the next sync uploads it.
     */
    fun resolveId(
        kind: LibraryClassifier,
        name: String,
        now: String = LibraryDuplicateMerger.nowDbDate(),
    ): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val key = LibraryDuplicateFinder.normalizedName(trimmed)

        // Read on every call rather than cached: these tables hold tens of rows, and a row created for
        // one recipe of a multi-recipe import has to be found by the next recipe that names it.
        rows(kind)
            .filter { (_, rowName) -> LibraryDuplicateFinder.normalizedName(rowName) == key }
            .minByOrNull { (id, _) -> id }
            ?.let { (id, _) -> return id }

        val id = newId()
        when (kind) {
            LibraryClassifier.COURSE -> q.upsertCourse(name = trimmed, lastModifiedDate = now, id = id)
            LibraryClassifier.CATEGORY -> q.upsertCategory(name = trimmed, lastModifiedDate = now, id = id)
            LibraryClassifier.TAG -> q.upsertTag(name = trimmed, lastModifiedDate = now, id = id)
        }
        return id
    }

    private fun rows(kind: LibraryClassifier): List<Pair<String, String?>> = when (kind) {
        LibraryClassifier.COURSE -> q.selectAllCourses().executeAsList().map { it.id to it.name }
        LibraryClassifier.CATEGORY -> q.selectAllCategories().executeAsList().map { it.id to it.name }
        LibraryClassifier.TAG -> q.selectAllTags().executeAsList().map { it.id to it.name }
    }
}
