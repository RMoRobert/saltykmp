package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.export.SaltyRecipeExport
import kotlinx.serialization.json.Json

/** The file handed to the importer isn't a `.saltyRecipe` document at all. */
class NotASaltyRecipeFileException : Exception("Not a Salty recipe file")

/**
 * Reads `.saltyRecipe` files — the decoding half of the Swift app's `SaltyRecipeImportHelper`. The
 * document is [SaltyRecipeExport]: one recipe as a JSON object, or several as an array of them (the Mac
 * app's Export… with more than one recipe selected writes the array).
 *
 * Deliberately more forgiving than Swift's decoder, which throws the whole file away over any one flaw:
 * - Keys this version doesn't know are ignored, so a file from a newer Salty still imports.
 * - A `null` where the document has a default reads as that default.
 * - A bad entry in an array costs that recipe, not the other eighty.
 * Dates are read leniently too, but that happens at import; see `SaltyRecipeImporter`.
 */
object SaltyRecipeFile {

    /**
     * Largest file the importer reads — the Swift app's `ImportFileLimits.maxRecipeFileBytes`. Generous
     * because photos travel base64-encoded: a real 82-recipe export is 62 MB.
     */
    const val MAX_FILE_BYTES: Long = 100L * 1024 * 1024

    /**
     * The spellings a file picker should offer. The Swift app declares `saltyRecipe`, but files do turn
     * up lowercased (a download renamed on the way), and some desktop pickers filter case-sensitively.
     */
    val FILE_EXTENSIONS: Set<String> = setOf("saltyRecipe", "saltyrecipe")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Every recipe in [bytes], in file order. A null element is an entry that is there but can't be
     * read as a recipe.
     *
     * Entries are decoded one at a time as the sequence is walked, so a large export never exists in
     * full as decoded recipes — or as one enormous string — at once: only the raw bytes stay resident.
     *
     * @throws NotASaltyRecipeFileException when [bytes] isn't a JSON object or array.
     */
    fun recipes(bytes: ByteArray): Sequence<SaltyRecipeExport?> {
        val start = contentStart(bytes)
        if (start == bytes.size || (bytes[start] != OPEN_BRACE && bytes[start] != OPEN_BRACKET)) {
            throw NotASaltyRecipeFileException()
        }
        return topLevelValues(bytes, start).asSequence().map { range ->
            runCatching {
                json.decodeFromString(SaltyRecipeExport.serializer(), bytes.decodeToString(range.first, range.last + 1))
            }.getOrNull()
        }
    }

    /** Index of the first byte that isn't a UTF-8 byte-order mark or whitespace. */
    private fun contentStart(bytes: ByteArray): Int {
        var i = if (bytes.size >= 3 && bytes[0] == BOM_0 && bytes[1] == BOM_1 && bytes[2] == BOM_2) 3 else 0
        while (i < bytes.size && bytes[i].isJsonWhitespace()) i++
        return i
    }
}

/**
 * Where the top-level values of the JSON text in [bytes] lie, found by scanning rather than parsing: the
 * elements when the value at [start] is an array, the whole value when it's anything else. Each range is
 * trimmed of surrounding whitespace.
 *
 * Scanning bytes is safe for UTF-8 because every byte of a multi-byte character is 0x80 or above, so
 * none can be mistaken for a quote, backslash, bracket or comma. Nothing here validates: a malformed
 * element comes back as a range that fails to decode, which is where it belongs. A file cut short
 * returns what was complete, plus the unfinished tail, which then fails to decode in the same way.
 */
internal fun topLevelValues(bytes: ByteArray, start: Int): List<IntRange> {
    if (start >= bytes.size) return emptyList()
    val values = mutableListOf<IntRange>()
    if (bytes[start] != OPEN_BRACKET) {
        addTrimmed(values, bytes, start, bytes.size)
        return values
    }

    var depth = 0
    var inString = false
    var escaped = false
    var elementStart = start + 1
    var i = start
    while (i < bytes.size) {
        val b = bytes[i]
        if (inString) {
            when {
                escaped -> escaped = false
                b == BACKSLASH -> escaped = true
                b == QUOTE -> inString = false
            }
        } else {
            when (b) {
                QUOTE -> inString = true
                OPEN_BRACE, OPEN_BRACKET -> depth++
                CLOSE_BRACE, CLOSE_BRACKET -> {
                    depth--
                    // The array's own closing bracket: its last element ends here.
                    if (depth == 0) {
                        addTrimmed(values, bytes, elementStart, i)
                        return values
                    }
                }
                COMMA -> if (depth == 1) {
                    addTrimmed(values, bytes, elementStart, i)
                    elementStart = i + 1
                }
            }
        }
        i++
    }
    addTrimmed(values, bytes, elementStart, bytes.size)
    return values
}

/** Adds `[from, until)` with surrounding whitespace removed, unless nothing is left of it. */
private fun addTrimmed(into: MutableList<IntRange>, bytes: ByteArray, from: Int, until: Int) {
    var first = from
    var last = until - 1
    while (first <= last && bytes[first].isJsonWhitespace()) first++
    while (last >= first && bytes[last].isJsonWhitespace()) last--
    if (first <= last) into += first..last
}

private fun Byte.isJsonWhitespace(): Boolean = this == SPACE || this == TAB || this == LF || this == CR

private const val OPEN_BRACE = '{'.code.toByte()
private const val CLOSE_BRACE = '}'.code.toByte()
private const val OPEN_BRACKET = '['.code.toByte()
private const val CLOSE_BRACKET = ']'.code.toByte()
private const val QUOTE = '"'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
private const val COMMA = ','.code.toByte()
private const val SPACE = ' '.code.toByte()
private const val TAB = '\t'.code.toByte()
private const val LF = '\n'.code.toByte()
private const val CR = '\r'.code.toByte()
private const val BOM_0 = 0xEF.toByte()
private const val BOM_1 = 0xBB.toByte()
private const val BOM_2 = 0xBF.toByte()
