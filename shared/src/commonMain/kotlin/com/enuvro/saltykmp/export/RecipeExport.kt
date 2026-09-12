package com.enuvro.saltykmp.export

import com.enuvro.saltykmp.api.ServerRecipe

/**
 * The three shapes a single recipe can leave the app as, matching the Swift app's Export… menu.
 *
 * [SALTY] is the lossless one and the only one that round-trips — its extension and MIME type are the
 * `com.inuvro.salty.recipe` UTType the Mac/iOS app registers, so a file written here is offered to
 * Salty when it lands on an Apple device. The other two are one-way: an interop format and a
 * human-readable one.
 */
enum class RecipeExportFormat(
    val label: String,
    val description: String,
    val extension: String,
    val mimeType: String,
) {
    SALTY(
        label = "Salty recipe file (.saltyRecipe)",
        description = "All recipe data, including photo. Opens in Salty on any device.",
        extension = "saltyRecipe",
        mimeType = "application/json",
    ),
    JSON_LD(
        label = "Schema.org JSON-LD (.json)",
        description = "Standard recipe format other apps and sites can read.",
        extension = "json",
        mimeType = "application/ld+json",
    ),
    TEXT(
        label = "Plain text (.txt)",
        description = "Readable anywhere — for email, messages, or printing.",
        extension = "txt",
        mimeType = "text/plain",
    ),
}

/**
 * The library-level facts an export needs that a recipe row doesn't carry: its course/category/tag
 * NAMES (the row holds ids, which mean nothing in another library) and its full image bytes (the row
 * holds a filename). Resolved by the caller so the exporters stay database-free and unit-testable.
 */
data class RecipeExportContext(
    val courseName: String? = null,
    val categoryNames: List<String> = emptyList(),
    val tagNames: List<String> = emptyList(),
    /** Full-size image bytes; carried by [RecipeExportFormat.SALTY] only. */
    val imageData: ByteArray? = null,
) {
    // ByteArray gives this class identity equality unless we spell it out, which would break any
    // equality-based test or `remember` key that happens to hold one.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecipeExportContext) return false
        return courseName == other.courseName &&
            categoryNames == other.categoryNames &&
            tagNames == other.tagNames &&
            imageData.contentEqualsOrNull(other.imageData)
    }

    override fun hashCode(): Int {
        var result = courseName?.hashCode() ?: 0
        result = 31 * result + categoryNames.hashCode()
        result = 31 * result + tagNames.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        if (this == null || other == null) this == null && other == null else contentEquals(other)
}

object RecipeExport {

    /** The file's contents in [format]. UTF-8 is what every one of the three formats is written in. */
    fun render(
        recipe: ServerRecipe,
        format: RecipeExportFormat,
        context: RecipeExportContext = RecipeExportContext(),
    ): String = when (format) {
        // The image is deliberately only in the .saltyRecipe document: JSON-LD has no place for local
        // bytes, and base64 in a text file would bury the recipe it's meant to make readable.
        RecipeExportFormat.SALTY -> saltyRecipeExport(recipe, context).toJsonString()
        RecipeExportFormat.JSON_LD -> SchemaOrgRecipeJsonLdExporter.render(recipe, context)
        RecipeExportFormat.TEXT -> saltyRecipeExport(recipe, context.copy(imageData = null)).toPlainText()
    }

    /**
     * A filename stem built from the recipe's name — no extension, because the save dialog and the
     * share sheet each append their own.
     *
     * Everything a filesystem could object to is folded to a space and runs are collapsed, which also
     * takes care of newlines pasted in from a web import. An empty or all-punctuation name falls back
     * to "Recipe" rather than producing a dotfile or an empty name.
     */
    fun filenameStem(recipeName: String): String {
        val cleaned = recipeName
            .map { if (it in ILLEGAL_FILENAME_CHARS || it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            // Leading dots hide the file on Unix; trailing dots and spaces are dropped by Windows.
            .trim('.', ' ')
        return cleaned.ifBlank { "Recipe" }.take(MAX_FILENAME_STEM)
    }

    private const val MAX_FILENAME_STEM = 80
    private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val WHITESPACE_RUN = Regex("""\s+""")
}
