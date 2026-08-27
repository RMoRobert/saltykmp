package com.enuvro.saltykmp.db

/**
 * The database half of the classifier editor's destructive actions: deleting courses, categories or
 * tags the user picked. Port of Salty's `LibraryClassifierEditor`. [LibraryDuplicateMerger] performs
 * the other one, since folding several rows into one is the same operation the duplicate scan runs.
 *
 * Kept separate from [com.enuvro.saltykmp.sync.LocalStore]'s `deleteCourse` / `deleteCategory` /
 * `deleteTag` on purpose. Those apply a deletion that ARRIVED from the server, where bumping the
 * local recipes' clocks would manufacture changes to push straight back at it. A deletion the user
 * made here has to move them: category and tag membership travels as the `categoryIds` / `tagIds`
 * arrays on the recipe payload, and a course as its `courseId`, so a classification that vanished
 * without the recipe's timestamp moving would never reach the server or the other devices.
 */
class LibraryClassifierEditor(private val db: AppDatabase) {

    private val q get() = db.queriesQueries

    /**
     * Deletes every row of [kind] in [ids] and returns how many recipes lost the classification. The
     * recipes themselves are never deleted.
     *
     * One transaction for the whole set: a half-applied bulk delete would leave junction rows
     * pointing at rows that are already gone.
     */
    fun delete(
        kind: LibraryClassifier,
        ids: Collection<String>,
        now: String = LibraryDuplicateMerger.nowDbDate(),
    ): Int {
        if (ids.isEmpty()) return 0
        var touched = 0
        q.transaction { touched = deleteInTransaction(kind, ids.toSet(), now) }
        return touched
    }

    private fun deleteInTransaction(kind: LibraryClassifier, ids: Set<String>, now: String): Int {
        val touched = mutableSetOf<String>()
        for (id in ids) {
            when (kind) {
                LibraryClassifier.CATEGORY -> {
                    touched += q.selectRecipeIdsForCategory(id).executeAsList()
                    q.deleteRecipeCategoriesByCategoryId(id)
                    q.deleteCategoryById(id)
                }

                LibraryClassifier.TAG -> {
                    touched += q.selectRecipeIdsForTag(id).executeAsList()
                    q.deleteRecipeTagsByTagId(id)
                    q.deleteTagById(id)
                }

                LibraryClassifier.COURSE -> {
                    // recipe.courseId is ON DELETE SET NULL, so the recipes are cleared by the delete
                    // itself — collect them first, then let the foreign key do the work. Rewriting the
                    // recipe rows here would re-write every column for nothing.
                    touched += q.selectRecipeIdsForCourse(id).executeAsList()
                    q.deleteCourseById(id)
                }
            }
        }
        touched.forEach { q.touchRecipeLastModified(lastModifiedDate = now, id = it) }
        return touched.size
    }
}
