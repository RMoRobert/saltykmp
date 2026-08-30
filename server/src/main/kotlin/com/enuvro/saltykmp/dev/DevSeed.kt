package com.enuvro.saltykmp.dev

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.export.SaltyRecipeExport
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.newId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Fills an empty library from `.saltyrecipe` files so local UI work has real content to click on.
 *
 * Opt-in by the presence of a directory, deliberately. A checkout without a seed directory never
 * runs this and never mentions it, so nobody inherits anyone else's recipes and CI stays empty --
 * which is also why the directory is gitignored rather than committed.
 *
 * Two guards make it safe to leave enabled:
 *  - it runs only when the target user has NO recipes, so it can never overwrite real data;
 *  - a file that fails to parse is skipped with a warning rather than taking the server down.
 *
 * The files are the same documents the Swift app writes, so [SaltyRecipeExport] is reused rather
 * than a parallel model. Reading is lenient about unknown keys (a newer app version may add some);
 * the shared `saltyRecipeJson` is tuned for Swift-compatible *writing* and is left alone.
 */
object DevSeed {

    private val log = LoggerFactory.getLogger(DevSeed::class.java)

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where seed files live, relative to the server's working directory. Override for tests. */
    const val DEFAULT_DIR = "seed"

    /**
     * Imports every recipe found in [dir] for [userId], or does nothing at all.
     *
     * @return how many recipes were created; 0 when the directory is absent or the library is not empty.
     */
    suspend fun seedIfRequested(dir: Path, imageStore: ImageStore, userId: String): Int {
        if (!Files.isDirectory(dir)) return 0          // the common case for other people's checkouts

        if (RecipeRepository.count(userId) > 0) {
            log.info("Seed directory present but the library already has recipes; leaving it alone.")
            return 0
        }

        val files = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.extension.lowercase() in setOf("saltyrecipe", "json") }
                .sorted()
                .toList()
        }
        if (files.isEmpty()) {
            log.info("Seed directory {} has no .saltyrecipe or .json files; nothing to import.", dir)
            return 0
        }

        var imported = 0
        var failed = 0
        for (file in files) {
            runCatching { importFile(file, imageStore, userId) }
                .onSuccess { imported += it }
                .onFailure { failed++; log.warn("Could not seed from ${file.name}: ${it.message}") }
        }
        log.info("Seeded {} recipe(s) from {}{}", imported, dir, if (failed > 0) " ($failed file(s) failed)" else "")
        return imported
    }

    /**
     * A file holds either one recipe or an array of them, matching what the Swift app exports for a
     * single recipe versus a multi-select.
     *
     * An array is streamed rather than parsed whole: a real library export runs to tens of megabytes
     * because every photo travels inline as base64, and holding all of that as a parsed tree at once
     * is a needless spike when the recipes are handled one at a time anyway.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun importFile(file: Path, imageStore: ImageStore, userId: String): Int {
        var count = 0
        Files.newInputStream(file).buffered().use { input ->
            json.decodeToSequence<SaltyRecipeExport>(input, DecodeSequenceMode.AUTO_DETECT).forEach { export ->
                runCatching { store(export, imageStore, userId) }
                    .onSuccess { count++ }
                    .onFailure { log.warn("Skipped \"${export.name}\": ${it.message}") }
            }
        }
        return count
    }

    private suspend fun store(export: SaltyRecipeExport, imageStore: ImageStore, userId: String) {
        val recipeId = export.id.ifBlank { newId() }

        // Course, categories and tags travel as NAMES: ids from another library mean nothing here.
        val courseId = export.course?.takeIf { it.isNotBlank() }?.let { resolveCourse(userId, it) }
        val categoryIds = export.categories.orEmpty().filter { it.isNotBlank() }.map { resolveCategory(userId, it) }
        val tagIds = export.tags.orEmpty().filter { it.isNotBlank() }.map { resolveTag(userId, it) }

        // Nested rows carry no ids in the file; the importer mints them, as the Swift app does.
        val ingredients = export.ingredients.map {
            Ingredient(id = newId(), text = it.text, isHeading = it.isHeading == true, isMain = it.isMain == true)
        }
        val directions = export.directions.map {
            Direction(id = newId(), text = it.text, isHeading = it.isHeading == true)
        }
        val preparationTimes = export.preparationTimes.map {
            PreparationTime(id = newId(), type = it.type, timeString = it.timeString)
        }
        val variations = export.variations.orEmpty().map {
            Variation(id = newId(), variationName = it.variationName, text = it.text)
        }

        // Exported stamps are second-precision (Swift's .iso8601 strategy drops the fraction);
        // normalise to the wire shape so seeded rows look exactly like synced ones.
        val stamp = { s: String? -> WireDate.format(WireDate.parse(s)) }
        val now = WireDate.format(WireDate.nowUtc())

        var recipe = ServerRecipe(
            id = recipeId,
            name = export.name,
            createdDate = stamp(export.createdDate) ?: now,
            lastModifiedDate = stamp(export.lastModifiedDate) ?: now,
            lastPrepared = stamp(export.lastPrepared),
            source = export.source,
            sourceDetails = export.sourceDetails,
            introduction = export.introduction,
            difficulty = export.difficulty,
            rating = export.rating,
            isFavorite = export.isFavorite,
            wantToMake = export.wantToMake,
            yield = export.yield,
            servings = export.servings,
            courseId = courseId,
            categoryIds = categoryIds,
            tagIds = tagIds,
            ingredients = ingredients,
            directions = directions,
            notes = export.notes,
            variations = variations,
            preparationTimes = preparationTimes,
            nutrition = export.nutrition,
        )

        // The photo is stored through ImageStore so it is resized and named exactly like an upload.
        // A bad image loses the picture, not the recipe.
        export.imageData?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = Base64.getDecoder().decode(b64.replace("\n", "").replace("\r", ""))
                val filename = imageStore.store(recipeId, bytes)
                recipe = recipe.copy(imageFilename = filename, lastModifiedImageDate = recipe.lastModifiedDate)
            }.onFailure { log.warn("No image for \"${export.name}\": ${it.message}") }
        }

        RecipeRepository.upsert(userId, recipe)
    }

    /* --- library lookups: find by name, create when missing, always stamped --- */

    private suspend fun resolveCourse(userId: String, name: String): String {
        LibraryRepository.listCourses(userId).firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
            ?.let { return it.id }
        val created = ServerCourse(newId(), name, WireDate.format(WireDate.nowUtc()))
        LibraryRepository.upsertCourse(userId, created)
        return created.id
    }

    private suspend fun resolveCategory(userId: String, name: String): String {
        LibraryRepository.listCategories(userId).firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
            ?.let { return it.id }
        val created = ServerCategory(newId(), name, WireDate.format(WireDate.nowUtc()))
        LibraryRepository.upsertCategory(userId, created)
        return created.id
    }

    private suspend fun resolveTag(userId: String, name: String): String {
        LibraryRepository.listTags(userId).firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
            ?.let { return it.id }
        val created = ServerTag(newId(), name, WireDate.format(WireDate.nowUtc()))
        LibraryRepository.upsertTag(userId, created)
        return created.id
    }
}
