package com.enuvro.saltykmp

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.Category
import com.enuvro.saltykmp.db.Course
import com.enuvro.saltykmp.db.LibraryClassifier
import com.enuvro.saltykmp.db.LibraryClassifierItem
import com.enuvro.saltykmp.db.Recipe
import com.enuvro.saltykmp.db.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Reactive reads over the local SQLDelight database for the UI. */
class RecipeRepository(db: AppDatabase) {
    private val q = db.queriesQueries

    /** Diagnostic: synchronous count of recipes actually readable from the opened DB. */
    fun debugRecipeCount(): Int = q.selectAllRecipesByName().executeAsList().size

    /** Default list ordering is by name; future work can add a sort parameter here (see Queries.sq). */
    fun recipes(): Flow<List<Recipe>> =
        q.selectAllRecipesByName().asFlow().mapToList(Dispatchers.Default)

    fun recipesForCourse(courseId: String): Flow<List<Recipe>> =
        q.selectRecipesByCourse(courseId).asFlow().mapToList(Dispatchers.Default)

    fun recipesForCategory(categoryId: String): Flow<List<Recipe>> =
        q.selectRecipesByCategory(categoryId).asFlow().mapToList(Dispatchers.Default)

    fun recipesForTag(tagId: String): Flow<List<Recipe>> =
        q.selectRecipesByTag(tagId).asFlow().mapToList(Dispatchers.Default)

    fun recipe(id: String): Recipe? = q.selectRecipeById(id).executeAsOneOrNull()

    /**
     * [id]'s row as it changes — re-read whenever the recipe table is written, and null once the recipe
     * is gone. For screens that must not show a stale recipe: the same one can be open in two windows,
     * and a sync can change it while it's on screen.
     */
    fun recipeFlow(id: String): Flow<Recipe?> =
        q.selectRecipeById(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    fun courses(): Flow<List<Course>> =
        q.selectAllCourses().asFlow().mapToList(Dispatchers.Default)

    fun categories(): Flow<List<Category>> =
        q.selectAllCategories().asFlow().mapToList(Dispatchers.Default)

    fun tags(): Flow<List<Tag>> =
        q.selectAllTags().asFlow().mapToList(Dispatchers.Default)

    /**
     * Every row of one classifier with the number of recipes using it — what the classifier editor
     * lists, and the count its merge and delete confirmations are written around.
     *
     * The count is part of the projection rather than a second query, so filing a recipe under a
     * category (or unfiling it) re-emits here: SQLDelight notifies on every table the statement
     * touches, junction tables included.
     */
    fun classifierItems(kind: LibraryClassifier): Flow<List<LibraryClassifierItem>> {
        val query = when (kind) {
            LibraryClassifier.CATEGORY -> q.selectCategoriesWithRecipeCounts(::classifierItem)
            LibraryClassifier.COURSE -> q.selectCoursesWithRecipeCounts(::classifierItem)
            LibraryClassifier.TAG -> q.selectTagsWithRecipeCounts(::classifierItem)
        }
        // Sorted here rather than in the SQL: the counting statements are shared with the post-sync
        // duplicate fold, which doesn't care about order, and SQLite's NOCASE is ASCII-only anyway.
        return query.asFlow().mapToList(Dispatchers.Default).map { it.sortedWith(BY_NAME) }
    }

    /**
     * recipeId → category names, for search and list subtitles. One query for the whole library rather
     * than a lookup per row; the map re-emits whenever a recipe/category/junction row changes.
     */
    fun categoryNamesByRecipe(): Flow<Map<String, List<String>>> =
        q.selectAllRecipeCategoryNames().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.groupBy({ it.recipeId }, { it.name.orEmpty() })
        }

    /** recipeId → tag names; see [categoryNamesByRecipe]. */
    fun tagNamesByRecipe(): Flow<Map<String, List<String>>> =
        q.selectAllRecipeTagNames().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.groupBy({ it.recipeId }, { it.name.orEmpty() })
        }
}

private fun classifierItem(id: String, name: String?, recipeCount: Long) =
    LibraryClassifierItem(id, name.orEmpty(), recipeCount.toInt())

/** Case-insensitive by name, then by name and id so equal-but-for-case rows keep a stable order. */
private val BY_NAME = compareBy<LibraryClassifierItem>({ it.name.lowercase() }, { it.name }, { it.id })
