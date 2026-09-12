package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.LibraryClassifier
import com.enuvro.saltykmp.db.LibraryClassifierResolver
import com.enuvro.saltykmp.db.LibraryDuplicateMerger
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.export.SaltyRecipeExport
import com.enuvro.saltykmp.sync.LocalStore
import com.enuvro.saltykmp.sync.SyncImagePreparer
import com.enuvro.saltykmp.util.newId
import com.enuvro.saltykmp.util.nowWireIso
import com.enuvro.saltykmp.util.wireIso
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A recipe an import added to the library. */
data class ImportedRecipe(val id: String, val name: String)

/**
 * Adds the recipes in `.saltyRecipe` files to the library — the writing half of the Swift app's
 * `SaltyRecipeImportHelper`, with the same rules:
 * - Every recipe is NEW. It gets a fresh id, as do its ingredient/direction/note rows, so importing a
 *   file twice gives two copies rather than one recipe fighting itself in sync.
 * - Course, categories and tags travel by name and are matched to this library's own rows, or created
 *   (see [LibraryClassifierResolver]).
 * - Dates come across as the file has them, so the recipe keeps its history in the date sorts. That is
 *   safe for sync because an imported row has never been agreed with the server: its agreement stamp is
 *   null, which uploads it however old its lastModifiedDate is.
 *
 * The photo is written through [saveImage] and thumbnailed through [makeThumbnail] — both platform
 * work, supplied by the app, the way `SyncService` takes its image sink.
 */
class SaltyRecipeImporter(
    private val db: AppDatabase,
    private val localStore: LocalStore,
    private val saveImage: (filename: String, bytes: ByteArray) -> Unit,
    private val makeThumbnail: (bytes: ByteArray) -> ByteArray?,
) {
    private val classifiers = LibraryClassifierResolver(db)

    /** What importing one file did. [skipped] counts entries that were in it but couldn't be imported. */
    class FileResult(val imported: List<ImportedRecipe>, val skipped: Int)

    /** A file as the picker handed it over: what to call it, how big it is, and how to read it. */
    class PickedFile(val name: String, val size: Long, val read: suspend () -> ByteArray)

    /**
     * Imports [files] in order, one at a time — so no two are in memory at once — and reports on all
     * of them. A file that yields nothing is a failure whatever the reason: too large to read, not a
     * Salty document, or holding no recipe that could be read.
     */
    suspend fun importFiles(files: List<PickedFile>, now: String = nowWireIso()): RecipeFileImportSummary {
        val imported = mutableListOf<ImportedRecipe>()
        val failed = mutableListOf<String>()
        var skipped = 0
        for (file in files) {
            val result = try {
                if (file.size > SaltyRecipeFile.MAX_FILE_BYTES) null else importFile(file.read(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a file too big for this device's heap fails as an
                // OutOfMemoryError while it is being read, before anything was written — reporting it
                // beats crashing, and the files after it may be fine.
                null
            }
            if (result == null || result.imported.isEmpty()) {
                failed += file.name
            } else {
                imported += result.imported
                skipped += result.skipped
            }
        }
        return RecipeFileImportSummary(imported, files.size, failed, skipped)
    }

    /**
     * Imports every recipe in one file's [bytes], one at a time.
     *
     * @throws NotASaltyRecipeFileException when [bytes] isn't a Salty recipe document.
     */
    fun importFile(bytes: ByteArray, now: String = nowWireIso()): FileResult {
        val imported = mutableListOf<ImportedRecipe>()
        var skipped = 0
        for (doc in SaltyRecipeFile.recipes(bytes)) {
            val recipe = doc?.let { runCatching { insert(it, now) }.getOrNull() }
            if (recipe == null) skipped++ else imported += recipe
        }
        return FileResult(imported, skipped)
    }

    /**
     * Adds [doc] as a new recipe. The row and its classifiers go in one transaction, so a failure part
     * way leaves no half-filed recipe and no orphaned new classifiers; the photo follows, and a photo
     * that can't be stored costs only the photo.
     */
    fun insert(doc: SaltyRecipeExport, now: String = nowWireIso()): ImportedRecipe {
        val id = newId()
        // The classifier tables are written directly, so they take the database's date format.
        val dbNow = LocalStore.wireToDbDate(now) ?: LibraryDuplicateMerger.nowDbDate()
        val recipe = db.transactionWithResult {
            val courseId = doc.course?.let { classifiers.resolveId(LibraryClassifier.COURSE, it, dbNow) }
            val categoryIds = doc.categories.orEmpty()
                .mapNotNull { classifiers.resolveId(LibraryClassifier.CATEGORY, it, dbNow) }
                .distinct()
            val tagIds = doc.tags.orEmpty()
                .mapNotNull { classifiers.resolveId(LibraryClassifier.TAG, it, dbNow) }
                .distinct()
            doc.toNewRecipe(id, courseId, categoryIds, tagIds, now).also { localStore.upsertRecipe(it) }
        }
        storeImage(id, doc.imageData, now)
        return ImportedRecipe(id, recipe.name)
    }

    private fun storeImage(recipeId: String, imageData: String?, now: String) {
        val bytes = decodeImageData(imageData) ?: return
        val thumbnail = runCatching { makeThumbnail(bytes) }.getOrNull()
        // Bytes no decoder here recognises and no platform codec could thumbnail are not a photo, and
        // storing them would give the recipe an image that every client fails to show. HEIC passes: it
        // is recognised even where it can't be decoded (desktop), and sync converts it where it can.
        if (thumbnail == null && SyncImagePreparer.detectContentType(bytes) == null) return
        val filename = "$recipeId.${imageExtension(bytes)}"
        runCatching {
            saveImage(filename, bytes)
            localStore.setRecipeImage(recipeId, filename, thumbnail, now)
        }
    }
}

/**
 * The new library row for [this] document. [id] is the recipe's new id; [rowId] mints the nested rows'
 * ids, and exists so tests can make them predictable.
 */
internal fun SaltyRecipeExport.toNewRecipe(
    id: String,
    courseId: String?,
    categoryIds: List<String>,
    tagIds: List<String>,
    now: String,
    rowId: () -> String = ::newId,
): ServerRecipe {
    val prepared = importedDate(lastPrepared)
    return ServerRecipe(
        id = id,
        // Neither editor saves a recipe without a name, so a nameless one gets a placeholder rather
        // than becoming a blank row in the list.
        name = name.trim().ifEmpty { "Untitled Recipe" },
        createdDate = importedDate(createdDate) ?: now,
        lastModifiedDate = importedDate(lastModifiedDate) ?: now,
        lastPrepared = prepared,
        // Stamped now, as setting the date by hand would be: it is the moment this library took the value.
        lastModifiedPreparedDate = prepared?.let { now },
        source = source?.ifBlank { null },
        sourceDetails = sourceDetails?.ifBlank { null },
        introduction = introduction?.ifBlank { null },
        difficulty = difficulty.takeIf { it in 1..5 },
        rating = rating.takeIf { it in 1..5 },
        isFavorite = isFavorite,
        wantToMake = wantToMake,
        yield = yield?.ifBlank { null },
        servings = servings,
        courseId = courseId,
        directions = directions.map { Direction(id = rowId(), isHeading = it.isHeading, text = it.text) },
        ingredients = ingredients.map {
            val heading = it.isHeading == true
            // Never main on a heading — the editors' rule, and what the bulk text grammar assumes.
            Ingredient(id = rowId(), isHeading = heading, isMain = it.isMain == true && !heading, text = it.text)
        },
        notes = notes.map { Note(id = rowId(), title = it.title, content = it.content) },
        variations = variations.orEmpty().map { Variation(id = rowId(), variationName = it.variationName, text = it.text) },
        preparationTimes = preparationTimes.map { PreparationTime(id = rowId(), type = it.type, timeString = it.timeString) },
        nutrition = nutrition,
        categoryIds = categoryIds,
        tagIds = tagIds,
    )
}

/**
 * A date from a file, in the strict wire shape; null when absent or unreadable. Salty writes
 * `2025-08-07T20:17:17Z`, but files exist with fractions and no zone at all
 * (`2025-08-07T02:21:53.000`); a zoneless stamp is UTC, as every stored Salty date is.
 */
@OptIn(ExperimentalTime::class)
internal fun importedDate(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: LocalStore.dbToWireDate(text)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    return wireIso(instant)
}

/**
 * The photo's bytes, from Swift's default base64 encoding of `Data`. Tolerates missing padding and
 * wrapped lines (some encoders break at 76 columns); null when there is no photo or it won't decode.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeImageData(base64: String?): ByteArray? {
    if (base64.isNullOrBlank()) return null
    val decoder = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    val bytes = runCatching { decoder.decode(base64) }.getOrNull()
        ?: runCatching { decoder.decode(base64.filterNot { it.isWhitespace() }) }.getOrNull()
    return bytes?.takeIf { it.isNotEmpty() }
}

/**
 * The extension to store a photo under, read from its bytes the way the Swift app's
 * `RecipeImageManager` chooses one — and like it, "jpg" when the format isn't recognised.
 */
internal fun imageExtension(bytes: ByteArray): String = when (SyncImagePreparer.detectContentType(bytes)) {
    SyncImagePreparer.PNG -> "png"
    SyncImagePreparer.GIF -> "gif"
    SyncImagePreparer.WEBP -> "webp"
    SyncImagePreparer.HEIC -> "heic"
    else -> "jpg"
}

/**
 * The outcome of one Import from File… across every file picked, and what to tell the user about it.
 * [failedFiles] names files that yielded nothing — unreadable, too large, not a Salty file, or holding
 * no importable recipe; [skipped] counts recipes that couldn't be imported from files that otherwise did.
 */
data class RecipeFileImportSummary(
    val imported: List<ImportedRecipe>,
    val fileCount: Int,
    val failedFiles: List<String>,
    val skipped: Int,
) {
    /** Everything picked came in. */
    val isClean: Boolean get() = imported.isNotEmpty() && failedFiles.isEmpty() && skipped == 0

    /** A heading for when [message] needs more than a snackbar. */
    val title: String get() = if (imported.isEmpty()) "Import Failed" else "Import Complete"

    /**
     * One line when everything came in — a snackbar's worth, unpunctuated like the app's other
     * snackbars — and sentences with the details otherwise.
     */
    val message: String
        get() {
            val headline = when {
                imported.isEmpty() -> "No recipes could be imported from the selected ${plural(fileCount, "file", withCount = false)}"
                imported.size == 1 && fileCount == 1 -> "Imported \"${imported.single().name}\""
                fileCount == 1 -> "Imported ${plural(imported.size, "recipe")}"
                else -> "Imported ${plural(imported.size, "recipe")} from $fileCount files"
            }
            val details = buildList {
                if (skipped > 0) add("${plural(skipped, "recipe")} couldn't be read.")
                if (failedFiles.isNotEmpty()) {
                    // By name, a few at most: the user needs to know which to look at, not a manifest.
                    val names = failedFiles.take(5).joinToString(", ") +
                        if (failedFiles.size > 5) ", and ${failedFiles.size - 5} more" else ""
                    add("Couldn't read ${plural(failedFiles.size, "file")} as Salty recipes: $names.")
                }
            }
            return when {
                details.isEmpty() && imported.isNotEmpty() -> headline
                else -> (listOf("$headline.") + details).joinToString("\n\n")
            }
        }

    private fun plural(n: Int, noun: String, withCount: Boolean = true): String {
        val word = if (n == 1) noun else "${noun}s"
        return if (withCount) "$n $word" else word
    }
}
