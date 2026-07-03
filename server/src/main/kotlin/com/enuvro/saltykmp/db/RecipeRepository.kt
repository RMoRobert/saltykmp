package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.appJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.ceil

data class RecipePage(
    val recipes: List<ServerRecipe>,
    val total: Long,
    val totalPages: Int,
    val pageNumber: Int,
)

object RecipeRepository {

    private fun condition(userId: String, modifiedSince: LocalDateTime?): SqlExpressionBuilder.() -> Op<Boolean> = {
        val base = Recipes.userId eq userId
        if (modifiedSince != null) base and (Recipes.lastModifiedDate greater modifiedSince) else base
    }

    suspend fun count(userId: String, modifiedSince: LocalDateTime? = null): Long = dbQuery {
        Recipes.selectAll().where(condition(userId, modifiedSince)).count()
    }

    /** A page (or the whole set when [page] is null) of recipes for the user, optionally delta-filtered. */
    suspend fun listForSync(
        userId: String,
        modifiedSince: LocalDateTime?,
        page: Int?,
        size: Int,
    ): RecipePage = dbQuery {
        val cond = condition(userId, modifiedSince)
        val total = Recipes.selectAll().where(cond).count()
        var query = Recipes.selectAll().where(cond).orderBy(Recipes.id to SortOrder.ASC)
        if (page != null) {
            query = query.limit(size).offset((page.toLong()) * size)
        }
        val rows = query.toList()
        val ids = rows.map { it[Recipes.id] }
        val cats = categoryIdsFor(ids)
        val tags = tagIdsFor(ids)
        val recipes = rows.map { rowToRecipe(it, cats[it[Recipes.id]].orEmpty(), tags[it[Recipes.id]].orEmpty()) }
        val totalPages = if (page == null || size <= 0) 1 else ceil(total.toDouble() / size).toInt()
        RecipePage(recipes, total, totalPages, page ?: 0)
    }

    suspend fun manifest(userId: String): List<RecipeManifestEntry> = dbQuery {
        Recipes.select(Recipes.id, Recipes.lastModifiedDate, Recipes.imageFilename, Recipes.lastModifiedImageDate)
            .where { Recipes.userId eq userId }
            .map {
                RecipeManifestEntry(
                    id = it[Recipes.id],
                    lastModifiedDate = WireDate.format(it[Recipes.lastModifiedDate]),
                    imageFilename = it[Recipes.imageFilename],
                    lastModifiedImageDate = WireDate.format(it[Recipes.lastModifiedImageDate]),
                )
            }
    }

    suspend fun getById(userId: String, id: String): ServerRecipe? = dbQuery {
        val row = Recipes.selectAll()
            .where { (Recipes.id eq id) and (Recipes.userId eq userId) }
            .limit(1).singleOrNull() ?: return@dbQuery null
        rowToRecipe(row, categoryIdsFor(listOf(id))[id].orEmpty(), tagIdsFor(listOf(id))[id].orEmpty())
    }

    suspend fun upsert(userId: String, recipe: ServerRecipe): ServerRecipe {
        dbQuery {
            // Image sub-record merge: the image (filename + its timestamp) is resolved independently of the
            // text body by lastModifiedImageDate, so a stale text-only upload can't clobber a newer image
            // (and vice versa). Keep whichever side's image is newer; an incoming null/older date preserves
            // what's stored (e.g. an image set via the dedicated /image endpoint that this body predates).
            val existing = Recipes.select(Recipes.imageFilename, Recipes.lastModifiedImageDate)
                .where { (Recipes.id eq recipe.id) and (Recipes.userId eq userId) }
                .limit(1).singleOrNull()
            val incomingImageDate = WireDate.parse(recipe.lastModifiedImageDate)
            val existingImageDate = existing?.get(Recipes.lastModifiedImageDate)
            val takeIncomingImage = existing == null ||
                (incomingImageDate != null && (existingImageDate == null || !incomingImageDate.isBefore(existingImageDate)))
            val mergedImageFilename = if (takeIncomingImage) recipe.imageFilename else existing.get(Recipes.imageFilename)
            val mergedImageDate = if (takeIncomingImage) incomingImageDate else existingImageDate
            Recipes.upsert {
            // Coalesce the columns the client treats as non-optional to concrete defaults, so a row is
            // always decodable client-side and we never store NULL where the app expects a value (mirrors
            // the Swift/KMP write path). Genuinely-optional columns (lastPrepared, image*, servings,
            // courseId, nutrition) stay nullable.
            it[id] = recipe.id
            it[Recipes.userId] = userId
            it[name] = recipe.name
            it[createdDate] = WireDate.parse(recipe.createdDate) ?: WireDate.nowUtc()
            it[lastModifiedDate] = WireDate.parse(recipe.lastModifiedDate) ?: WireDate.nowUtc()
            it[lastPrepared] = WireDate.parse(recipe.lastPrepared)
            it[sourceText] = recipe.source ?: ""
            it[sourceDetails] = recipe.sourceDetails ?: ""
            it[introduction] = recipe.introduction ?: ""
            it[difficulty] = recipe.difficulty ?: 0
            it[rating] = recipe.rating ?: 0
            it[imageFilename] = mergedImageFilename
            it[lastModifiedImageDate] = mergedImageDate
            it[isFavorite] = recipe.isFavorite ?: false
            it[wantToMake] = recipe.wantToMake ?: false
            it[yield] = recipe.yield ?: ""
            it[servings] = recipe.servings
            it[courseId] = recipe.courseId
            it[directions] = encodeList(recipe.directions ?: emptyList(), Direction.serializer())
            it[ingredients] = encodeList(recipe.ingredients ?: emptyList(), Ingredient.serializer())
            it[notes] = encodeList(recipe.notes ?: emptyList(), Note.serializer())
            it[variations] = encodeList(recipe.variations ?: emptyList(), Variation.serializer())
            it[preparationTimes] = encodeList(recipe.preparationTimes ?: emptyList(), PreparationTime.serializer())
                it[nutrition] = recipe.nutrition?.let { n -> appJson.encodeToString(NutritionInformation.serializer(), n) }
            }
            // Junctions: only replace when the client actually sent associations.
            recipe.categoryIds?.let { replaceCategories(recipe.id, it) }
            recipe.tagIds?.let { replaceTags(recipe.id, it) }
        }
        // Read back in a fresh (committed-visible) transaction.
        return getById(userId, recipe.id)!!
    }

    suspend fun delete(userId: String, id: String): Boolean = dbQuery {
        val n = Recipes.deleteWhere { (Recipes.id eq id) and (Recipes.userId eq userId) }
        if (n > 0) {
            RecipeCategories.deleteWhere { RecipeCategories.recipeId eq id }
            RecipeTags.deleteWhere { RecipeTags.recipeId eq id }
        }
        n > 0
    }

    suspend fun deleteMany(userId: String, ids: List<String>): Int {
        var deleted = 0
        for (id in ids) if (delete(userId, id)) deleted++
        return deleted
    }

    suspend fun imageFilename(userId: String, id: String): String? = dbQuery {
        Recipes.select(Recipes.imageFilename)
            .where { (Recipes.id eq id) and (Recipes.userId eq userId) }
            .limit(1).singleOrNull()?.get(Recipes.imageFilename)
    }

    /** Sets the image filename and stamps the image timestamp. [imageDate] is the client-authoritative
     * lastModifiedImageDate (stored verbatim so the uploading device never re-pulls its own image);
     * falls back to server time when the client didn't send one. */
    suspend fun setImageFilename(userId: String, id: String, filename: String?, imageDate: LocalDateTime? = null): Boolean = dbQuery {
        Recipes.update({ (Recipes.id eq id) and (Recipes.userId eq userId) }) {
            it[imageFilename] = filename
            it[lastModifiedImageDate] = imageDate ?: WireDate.nowUtc()
        } > 0
    }

    // ---- helpers ----

    private fun categoryIdsFor(recipeIds: List<String>): Map<String, List<String>> {
        if (recipeIds.isEmpty()) return emptyMap()
        return RecipeCategories.selectAll().where { RecipeCategories.recipeId inList recipeIds }
            .groupBy({ it[RecipeCategories.recipeId] }, { it[RecipeCategories.categoryId] })
    }

    private fun tagIdsFor(recipeIds: List<String>): Map<String, List<String>> {
        if (recipeIds.isEmpty()) return emptyMap()
        return RecipeTags.selectAll().where { RecipeTags.recipeId inList recipeIds }
            .groupBy({ it[RecipeTags.recipeId] }, { it[RecipeTags.tagId] })
    }

    private fun replaceCategories(recipeId: String, categoryIds: List<String>) {
        RecipeCategories.deleteWhere { RecipeCategories.recipeId eq recipeId }
        for (catId in categoryIds.distinct()) {
            RecipeCategories.insert {
                it[id] = UUID.randomUUID().toString()
                it[RecipeCategories.recipeId] = recipeId
                it[categoryId] = catId
            }
        }
    }

    private fun replaceTags(recipeId: String, tagIds: List<String>) {
        RecipeTags.deleteWhere { RecipeTags.recipeId eq recipeId }
        for (tagId in tagIds.distinct()) {
            RecipeTags.insert {
                it[id] = UUID.randomUUID().toString()
                it[RecipeTags.recipeId] = recipeId
                it[RecipeTags.tagId] = tagId
            }
        }
    }

    private fun rowToRecipe(row: ResultRow, categoryIds: List<String>, tagIds: List<String>): ServerRecipe =
        ServerRecipe(
            id = row[Recipes.id],
            name = row[Recipes.name],
            createdDate = WireDate.format(row[Recipes.createdDate]),
            lastModifiedDate = WireDate.format(row[Recipes.lastModifiedDate]),
            lastPrepared = WireDate.format(row[Recipes.lastPrepared]),
            source = row[Recipes.sourceText],
            sourceDetails = row[Recipes.sourceDetails],
            introduction = row[Recipes.introduction],
            difficulty = row[Recipes.difficulty],
            rating = row[Recipes.rating],
            imageFilename = row[Recipes.imageFilename],
            lastModifiedImageDate = WireDate.format(row[Recipes.lastModifiedImageDate]),
            isFavorite = row[Recipes.isFavorite],
            wantToMake = row[Recipes.wantToMake],
            yield = row[Recipes.yield],
            servings = row[Recipes.servings],
            courseId = row[Recipes.courseId],
            directions = decodeList(row[Recipes.directions], Direction.serializer()),
            ingredients = decodeList(row[Recipes.ingredients], Ingredient.serializer()),
            notes = decodeList(row[Recipes.notes], Note.serializer()),
            variations = decodeList(row[Recipes.variations], Variation.serializer()),
            preparationTimes = decodeList(row[Recipes.preparationTimes], PreparationTime.serializer()),
            nutrition = row[Recipes.nutrition]?.let { appJson.decodeFromString(NutritionInformation.serializer(), it) },
            categoryIds = categoryIds,
            tagIds = tagIds,
        )

    private fun <T> decodeList(text: String?, serializer: KSerializer<T>): List<T>? =
        text?.takeIf { it.isNotBlank() }?.let { appJson.decodeFromString(ListSerializer(serializer), it) }

    private fun <T> encodeList(list: List<T>?, serializer: KSerializer<T>): String? =
        list?.let { appJson.encodeToString(ListSerializer(serializer), it) }
}
