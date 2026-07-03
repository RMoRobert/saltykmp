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
}
