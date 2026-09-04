package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.LibraryDeleteResponse
import com.enuvro.saltykmp.api.LibraryMergeResponse
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.util.WireDate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDateTime
import java.util.UUID

/** Courses / categories / tags — small user-scoped classifier tables (same shape). */
object LibraryRepository {

    // Courses
    suspend fun listCourses(userId: String): List<ServerCourse> = dbQuery {
        Courses.selectAll().where { Courses.userId eq userId }.orderBy(Courses.name to SortOrder.ASC)
            .map { ServerCourse(it[Courses.id], it[Courses.name], WireDate.format(it[Courses.lastModifiedDate])) }
    }
    suspend fun countCourses(userId: String): Long = dbQuery {
        Courses.selectAll().where { Courses.userId eq userId }.count()
    }
    suspend fun upsertCourse(userId: String, c: ServerCourse): ServerCourse = dbQuery {
        Courses.requireNotOwnedByAnother(Courses.id, Courses.userId, c.id, userId, "course")
        Courses.upsert {
            it[id] = c.id; it[Courses.userId] = userId; it[name] = c.name
            it[lastModifiedDate] = WireDate.parse(c.lastModifiedDate) ?: WireDate.nowUtc()
        }
        c
    }
    suspend fun deleteCourse(userId: String, id: String): Boolean = dbQuery {
        Courses.deleteWhere { (Courses.id eq id) and (Courses.userId eq userId) } > 0
    }

    // Categories
    suspend fun listCategories(userId: String): List<ServerCategory> = dbQuery {
        Categories.selectAll().where { Categories.userId eq userId }.orderBy(Categories.name to SortOrder.ASC)
            .map { ServerCategory(it[Categories.id], it[Categories.name], WireDate.format(it[Categories.lastModifiedDate])) }
    }
    suspend fun countCategories(userId: String): Long = dbQuery {
        Categories.selectAll().where { Categories.userId eq userId }.count()
    }
    suspend fun upsertCategory(userId: String, c: ServerCategory): ServerCategory = dbQuery {
        Categories.requireNotOwnedByAnother(Categories.id, Categories.userId, c.id, userId, "category")
        Categories.upsert {
            it[id] = c.id; it[Categories.userId] = userId; it[name] = c.name
            it[lastModifiedDate] = WireDate.parse(c.lastModifiedDate) ?: WireDate.nowUtc()
        }
        c
    }
    suspend fun deleteCategory(userId: String, id: String): Boolean = dbQuery {
        Categories.deleteWhere { (Categories.id eq id) and (Categories.userId eq userId) } > 0
    }

    // Tags
    suspend fun listTags(userId: String): List<ServerTag> = dbQuery {
        Tags.selectAll().where { Tags.userId eq userId }.orderBy(Tags.name to SortOrder.ASC)
            .map { ServerTag(it[Tags.id], it[Tags.name], WireDate.format(it[Tags.lastModifiedDate])) }
    }
    suspend fun countTags(userId: String): Long = dbQuery {
        Tags.selectAll().where { Tags.userId eq userId }.count()
    }
    suspend fun upsertTag(userId: String, t: ServerTag): ServerTag = dbQuery {
        Tags.requireNotOwnedByAnother(Tags.id, Tags.userId, t.id, userId, "tag")
        Tags.upsert {
            it[id] = t.id; it[Tags.userId] = userId; it[name] = t.name
            it[lastModifiedDate] = WireDate.parse(t.lastModifiedDate) ?: WireDate.nowUtc()
        }
        t
    }
    suspend fun deleteTag(userId: String, id: String): Boolean = dbQuery {
        Tags.deleteWhere { (Tags.id eq id) and (Tags.userId eq userId) } > 0
    }

    // ---- Merging ----
    //
    // The server-side twin of the clients' LibraryDuplicateMerger: fold several rows into one without
    // any recipe losing its classification. Everything happens in ONE transaction, because a
    // half-applied merge would leave recipes pointing at a row that is already gone.
    //
    // What makes it safe to sync back is the recipe stamp. Category and tag membership travel on the
    // recipe payload (`categoryIds` / `tagIds`), and a course as its `courseId`; the classifier rows
    // themselves are reconciled by id. So on a client's next sync the duplicate vanishes from the
    // list and is deleted locally (its local junction rows cascade), and every recipe that was
    // re-pointed carries a `lastModifiedDate` of now -- newer than the copy the client holds -- and
    // is downloaded with the survivor in place. A re-point that left the stamp alone would never
    // leave the server: the client would delete the duplicate and the recipe would simply lose it.
    //
    // The survivor is not touched at all -- not renamed, not restamped -- so no client re-downloads
    // it, and its exact spelling is what remains, which is the reason the caller chooses it.

    /** Folds [duplicateIds] into [survivorId]. Null when the survivor is not this user's (a 404). */
    suspend fun mergeCourses(
        userId: String,
        survivorId: String,
        duplicateIds: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryMergeResponse? = dbQuery {
        if (!owns(Courses, Courses.id, Courses.userId, userId, survivorId)) return@dbQuery null
        val duplicates = owned(Courses, Courses.id, Courses.userId, userId, duplicateIds - survivorId)
        val touched = LinkedHashSet<String>()
        for (dup in duplicates) {
            // A recipe has one course, so this is a straight re-point of the column -- scoped to the
            // user, since nothing stops a stray upload from naming another account's course id.
            val mine = { (Recipes.courseId eq dup) and (Recipes.userId eq userId) }
            touched += Recipes.select(Recipes.id).where(mine).map { it[Recipes.id] }
            Recipes.update(mine) { it[courseId] = survivorId; it[lastModifiedDate] = now }
            Courses.deleteWhere { (Courses.id eq dup) and (Courses.userId eq userId) }
        }
        LibraryMergeResponse(survivorId, duplicates, touched.toList())
    }

    suspend fun mergeCategories(
        userId: String,
        survivorId: String,
        duplicateIds: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryMergeResponse? = dbQuery {
        if (!owns(Categories, Categories.id, Categories.userId, userId, survivorId)) return@dbQuery null
        val duplicates = owned(Categories, Categories.id, Categories.userId, userId, duplicateIds - survivorId)
        val touched = LinkedHashSet<String>()
        for (dup in duplicates) {
            touched += foldJunction(
                userId, RecipeCategories, RecipeCategories.id, RecipeCategories.recipeId, RecipeCategories.categoryId,
                survivorId = survivorId, duplicateId = dup,
            )
            Categories.deleteWhere { (Categories.id eq dup) and (Categories.userId eq userId) }
        }
        touchRecipes(userId, touched, now)
        LibraryMergeResponse(survivorId, duplicates, touched.toList())
    }

    suspend fun mergeTags(
        userId: String,
        survivorId: String,
        duplicateIds: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryMergeResponse? = dbQuery {
        if (!owns(Tags, Tags.id, Tags.userId, userId, survivorId)) return@dbQuery null
        val duplicates = owned(Tags, Tags.id, Tags.userId, userId, duplicateIds - survivorId)
        val touched = LinkedHashSet<String>()
        for (dup in duplicates) {
            touched += foldJunction(
                userId, RecipeTags, RecipeTags.id, RecipeTags.recipeId, RecipeTags.tagId,
                survivorId = survivorId, duplicateId = dup,
            )
            Tags.deleteWhere { (Tags.id eq dup) and (Tags.userId eq userId) }
        }
        touchRecipes(userId, touched, now)
        LibraryMergeResponse(survivorId, duplicates, touched.toList())
    }

    // ---- Deleting several at once ----
    //
    // The user-facing delete, and the twin of the clients' LibraryClassifierEditor. The plain
    // DELETE /{id} above is what the sync passes call to apply a deletion a CLIENT already made --
    // that client has moved its own recipes' stamps, and the recipes follow on the same sync. A
    // deletion made HERE has no such client behind it, so this one does the recipes' side itself:
    // junction rows cleared (or the course column set null) and every recipe that lost the
    // classification restamped to now, so the loss reaches the other devices on the recipe payload.
    //
    // A client that sees the row vanish from the list deletes it locally and cascades the junction
    // rows, which usually amounts to the same thing -- but not always. A client refuses to delete
    // locally when the server lists NO rows of that kind (its guard against an empty list reading
    // as "delete everything"), so deleting the last category would otherwise never reach it, and
    // that device would keep classifying the recipe with a row the rest of the library has lost.

    suspend fun deleteCourses(
        userId: String,
        ids: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryDeleteResponse = dbQuery {
        val removed = owned(Courses, Courses.id, Courses.userId, userId, ids)
        val touched = LinkedHashSet<String>()
        for (id in removed) {
            val mine = { (Recipes.courseId eq id) and (Recipes.userId eq userId) }
            touched += Recipes.select(Recipes.id).where(mine).map { it[Recipes.id] }
            Recipes.update(mine) { it[courseId] = null; it[lastModifiedDate] = now }
            Courses.deleteWhere { (Courses.id eq id) and (Courses.userId eq userId) }
        }
        LibraryDeleteResponse(removed, touched.toList())
    }

    suspend fun deleteCategories(
        userId: String,
        ids: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryDeleteResponse = dbQuery {
        val removed = owned(Categories, Categories.id, Categories.userId, userId, ids)
        val touched = LinkedHashSet<String>()
        for (id in removed) {
            touched += clearJunction(userId, RecipeCategories, RecipeCategories.recipeId, RecipeCategories.categoryId, id)
            Categories.deleteWhere { (Categories.id eq id) and (Categories.userId eq userId) }
        }
        touchRecipes(userId, touched, now)
        LibraryDeleteResponse(removed, touched.toList())
    }

    suspend fun deleteTags(
        userId: String,
        ids: List<String>,
        now: LocalDateTime = WireDate.nowUtc(),
    ): LibraryDeleteResponse = dbQuery {
        val removed = owned(Tags, Tags.id, Tags.userId, userId, ids)
        val touched = LinkedHashSet<String>()
        for (id in removed) {
            touched += clearJunction(userId, RecipeTags, RecipeTags.recipeId, RecipeTags.tagId, id)
            Tags.deleteWhere { (Tags.id eq id) and (Tags.userId eq userId) }
        }
        touchRecipes(userId, touched, now)
        LibraryDeleteResponse(removed, touched.toList())
    }

    /** Removes every junction row naming [classifierId]; returns the user's recipes that lost it. */
    private fun clearJunction(
        userId: String,
        junction: Table,
        recipeColumn: Column<String>,
        classifierColumn: Column<String>,
        classifierId: String,
    ): List<String> {
        val mine = ownedRecipesReferencing(userId, junction, recipeColumn, classifierColumn, classifierId)
        junction.deleteWhere { classifierColumn eq classifierId }
        return mine
    }

    /**
     * Moves every junction row naming [duplicateId] onto [survivorId] and returns the recipes that
     * changed. A recipe that already held the survivor keeps ONE row, not two: the pair is not
     * unique in the schema, and a double row would come back as a doubled id in `categoryIds`.
     *
     * Only the user's own recipes are re-pointed; the duplicate's junction rows are removed
     * regardless, since the row they name is about to go.
     */
    private fun foldJunction(
        userId: String,
        junction: Table,
        idColumn: Column<String>,
        recipeColumn: Column<String>,
        classifierColumn: Column<String>,
        survivorId: String,
        duplicateId: String,
    ): List<String> {
        val mine = ownedRecipesReferencing(userId, junction, recipeColumn, classifierColumn, duplicateId)
        val alreadyHaveSurvivor = if (mine.isEmpty()) emptySet() else {
            junction.select(recipeColumn)
                .where { (classifierColumn eq survivorId) and (recipeColumn inList mine) }
                .map { it[recipeColumn] }.toSet()
        }
        for (recipeId in mine) {
            if (recipeId in alreadyHaveSurvivor) continue
            junction.insert {
                it[idColumn] = UUID.randomUUID().toString()
                it[recipeColumn] = recipeId
                it[classifierColumn] = survivorId
            }
        }
        junction.deleteWhere { classifierColumn eq duplicateId }
        return mine
    }

    /**
     * The user's own recipes with a junction row naming [classifierId]. Scoped to the user because the
     * junction tables carry no user of their own, and nothing stops a stray upload from naming
     * another account's row.
     */
    private fun ownedRecipesReferencing(
        userId: String,
        junction: Table,
        recipeColumn: Column<String>,
        classifierColumn: Column<String>,
        classifierId: String,
    ): List<String> {
        val referencing = junction.select(recipeColumn).where { classifierColumn eq classifierId }
            .map { it[recipeColumn] }.distinct()
        if (referencing.isEmpty()) return emptyList()
        return Recipes.select(Recipes.id).where { (Recipes.id inList referencing) and (Recipes.userId eq userId) }
            .map { it[Recipes.id] }
    }

    private fun touchRecipes(userId: String, ids: Collection<String>, now: LocalDateTime) {
        if (ids.isEmpty()) return
        Recipes.update({ (Recipes.id inList ids.toList()) and (Recipes.userId eq userId) }) {
            it[lastModifiedDate] = now
        }
    }

    private fun owns(table: Table, idColumn: Column<String>, userColumn: Column<String>, userId: String, id: String): Boolean =
        table.select(idColumn).where { (idColumn eq id) and (userColumn eq userId) }.limit(1).any()

    /** The ids in [ids] that are this user's rows, in the order given. Rows already gone are dropped. */
    private fun owned(table: Table, idColumn: Column<String>, userColumn: Column<String>, userId: String, ids: List<String>): List<String> {
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return emptyList()
        val present = table.select(idColumn).where { (idColumn inList wanted) and (userColumn eq userId) }
            .map { it[idColumn] }.toSet()
        return wanted.filter { it in present }
    }
}
