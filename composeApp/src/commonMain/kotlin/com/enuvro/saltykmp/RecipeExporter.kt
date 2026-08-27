package com.enuvro.saltykmp

import com.enuvro.saltykmp.di.ExportOutcome
import com.enuvro.saltykmp.di.deliverExportedFile
import com.enuvro.saltykmp.export.RecipeExport
import com.enuvro.saltykmp.export.RecipeExportContext
import com.enuvro.saltykmp.export.RecipeExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns one stored recipe into a file and hands it to the user — a save dialog on desktop, the share
 * sheet on mobile ([deliverExportedFile]).
 *
 * The rendering itself lives in `shared` and is unit-tested there; this is the layer that reads the
 * database, because the exporters deliberately don't.
 */
object RecipeExporter {

    /** Renders [recipeId] as [format] and delivers it. Returns a message to show the user. */
    suspend fun export(module: AppModule, recipeId: String, format: RecipeExportFormat): String {
        // Reading the recipe, loading its image and base64-ing it are off the main thread; DELIVERY is
        // not, because iOS's share sheet is UIKit and has to be presented from the main thread (callers
        // are composition scopes, so that is where this resumes).
        val file = withContext(Dispatchers.Default) { render(module, recipeId, format) }
            ?: return "Couldn't export — that recipe is no longer in your library."

        val outcome = deliverExportedFile(file.stem, format.extension, file.bytes)
        return message(outcome, file.recipeName, format)
    }

    /** A rendered export, ready to hand over. Null when the recipe has gone (deleted, or synced away). */
    private fun render(module: AppModule, recipeId: String, format: RecipeExportFormat): RenderedExport? {
        val recipe = module.localStore.recipeForUpload(recipeId) ?: return null

        val context = RecipeExportContext(
            // The row stores library ids; a file leaving this device has to carry names, since ids mean
            // nothing in the library it lands in.
            courseName = recipe.courseId?.let { id ->
                module.localStore.courses().firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
            },
            categoryNames = namesFor(recipe.categoryIds, module.localStore.categories().associate { it.id to it.name }),
            tagNames = namesFor(recipe.tagIds, module.localStore.tags().associate { it.id to it.name }),
            // Full image, not the cached thumbnail: this is the copy someone else will cook from. A
            // missing image file is not a failed export — the rest of the recipe is still worth sending.
            imageData = if (format.carriesImage) {
                recipe.imageFilename?.let { runCatching { module.imageFiles.load(it) }.getOrNull() }
            } else {
                null
            },
        )

        return RenderedExport(
            recipeName = recipe.name,
            stem = RecipeExport.filenameStem(recipe.name),
            bytes = RecipeExport.render(recipe, format, context).encodeToByteArray(),
        )
    }

    private class RenderedExport(val recipeName: String, val stem: String, val bytes: ByteArray)

    /** Names for [ids], sorted case-insensitively as the Swift exporter sorts them; blanks dropped. */
    private fun namesFor(ids: List<String>?, namesById: Map<String, String?>): List<String> =
        ids.orEmpty()
            .mapNotNull { namesById[it]?.takeIf { name -> name.isNotBlank() } }
            .sortedWith(CASE_INSENSITIVE)

    private fun message(outcome: ExportOutcome, recipeName: String, format: RecipeExportFormat): String =
        when (outcome) {
            is ExportOutcome.Saved -> "Exported \"$recipeName\" to ${outcome.location}"
            // The share sheet doesn't tell us which target the user picked, or whether they backed out,
            // so this says what the app did rather than claiming the file arrived anywhere.
            ExportOutcome.Shared -> "Shared \"$recipeName\" as ${format.label.lowercase()}"
            ExportOutcome.Cancelled -> ""
            is ExportOutcome.Failed -> "Couldn't export \"$recipeName\"" +
                (outcome.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
        }
}

/** Only the Salty document carries the photo — see `RecipeExport.render`. */
private val RecipeExportFormat.carriesImage: Boolean
    get() = this == RecipeExportFormat.SALTY

/**
 * Case-insensitive ordering for the classifier names in an export. `String.CASE_INSENSITIVE_ORDER` is
 * JVM-only, so this is the multiplatform stand-in; ties fall back to the case-sensitive comparison so
 * the order is total (and therefore identical on every platform).
 */
private val CASE_INSENSITIVE: Comparator<String> = compareBy<String> { it.lowercase() }.thenBy { it }
