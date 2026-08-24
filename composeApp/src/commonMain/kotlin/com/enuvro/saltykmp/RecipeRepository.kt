package com.enuvro.saltykmp

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.Category
import com.enuvro.saltykmp.db.Course
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

    fun courses(): Flow<List<Course>> =
        q.selectAllCourses().asFlow().mapToList(Dispatchers.Default)

    fun categories(): Flow<List<Category>> =
        q.selectAllCategories().asFlow().mapToList(Dispatchers.Default)

    fun tags(): Flow<List<Tag>> =
        q.selectAllTags().asFlow().mapToList(Dispatchers.Default)

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
