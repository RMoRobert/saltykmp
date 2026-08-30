package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.util.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A recipe parsed out of a page's schema.org JSON-LD, plus page metadata that isn't part of the recipe. */
data class ParsedRecipe(
    val name: String,
    val source: String = "",
    val sourceDetails: String = "",
    val introduction: String = "",
    val yield: String = "",
    val servings: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val directions: List<Direction> = emptyList(),
    val preparationTimes: List<PreparationTime> = emptyList(),
    /** URL of the recipe photo declared in the JSON-LD, if any. The import flow downloads it separately. */
    val imageUrl: String? = null,
)

/**
 * Extracts schema.org `Recipe` objects from a page's JSON-LD, mirroring the Swift app's
 * `SchemaOrgRecipeJSONLDImporter` (the same field mapping, so the same page imports the same way on both
 * platforms). Pure and I/O-free: [RecipeWebImporter] does the fetching.
 *
 * There is no HTML parser in the shared module, so `<script type="application/ld+json">` blocks are located
 * by scanning rather than by parsing the document. That is sufficient here — we only need the script bodies,
 * and everything inside them is real JSON — but it means malformed markup degrades to "found nothing"
 * instead of throwing.
 *
 * Everything it reads is untrusted web content, so the same limits the Swift importer applies are enforced:
 * input size, script count, per-field length, and array length.
 */
object SchemaOrgRecipeParser {

    object Limits {
        const val MAX_INPUT_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_SCRIPT_TAGS = 50
        const val MAX_FIELD_LENGTH = 20_000
        const val MAX_ARRAY_ITEMS = 1_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every schema.org Recipe found in [html], in document order. Empty when there is none. */
    fun parse(html: String): List<ParsedRecipe> {
        if (html.length > Limits.MAX_INPUT_BYTES) return emptyList()
        return jsonLdBlocks(html).flatMap { block ->
            val root = runCatching { json.parseToJsonElement(block) }.getOrNull()
            root?.let { recipesIn(it) }.orEmpty()
        }
    }

    /**
     * Bodies of the `<script type="application/ld+json">` elements. Matching is deliberately loose about
     * attribute order and quoting, since real pages vary; the closing `</script>` ends the body.
     */
    private fun jsonLdBlocks(html: String): List<String> {
        val blocks = mutableListOf<String>()
        var i = 0
        while (blocks.size < Limits.MAX_SCRIPT_TAGS) {
            val open = html.indexOf("<script", i, ignoreCase = true)
            if (open < 0) break
            val openEnd = html.indexOf('>', open)
            if (openEnd < 0) break
            val attrs = html.substring(open, openEnd)
            i = openEnd + 1
            val close = html.indexOf("</script", i, ignoreCase = true)
            if (close < 0) break
            if (attrs.contains("application/ld+json", ignoreCase = true)) {
                blocks += html.substring(i, close)
            }
            i = close + 1
        }
        return blocks
    }

    /** Recipes directly in [element], inside a top-level array, or nested in an `@graph` (Cookie&Kate et al.). */
    private fun recipesIn(element: JsonElement): List<ParsedRecipe> = when (element) {
        is JsonArray -> element.flatMap { recipesIn(it) }
        is JsonObject -> buildList {
            toRecipe(element)?.let { add(it) }
            (element["@graph"] as? JsonArray)?.forEach { node -> addAll(recipesIn(node)) }
        }
        else -> emptyList()
    }

    /** `@type` is a string on most sites and an array on some; both must be accepted. */
    private fun isRecipe(obj: JsonObject): Boolean = when (val type = obj["@type"]) {
        is JsonPrimitive -> type.contentOrNull == "Recipe"
        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull == "Recipe" }
        else -> false
    }

    private fun toRecipe(obj: JsonObject): ParsedRecipe? {
        if (!isRecipe(obj)) return null
        return ParsedRecipe(
            name = obj.string("name").orEmpty(),
            source = author(obj),
            sourceDetails = obj.string("url").orEmpty(),
            introduction = obj.string("description").orEmpty(),
            yield = obj.string("recipeYield").orEmpty(),
            servings = servings(obj),
            ingredients = obj.stringList("recipeIngredient").map {
                Ingredient(id = newId(), isHeading = false, isMain = false, text = it)
            },
            directions = directions(obj),
            preparationTimes = preparationTimes(obj),
            imageUrl = imageUrl(obj),
        )
    }

    /** `author` may be a string, an object with `name`, or an array of either. */
    private fun author(obj: JsonObject): String = when (val a = obj["author"]) {
        is JsonPrimitive -> a.contentOrNull?.let(::clean).orEmpty()
        is JsonObject -> a.string("name").orEmpty()
        is JsonArray -> a.mapNotNull { node ->
            when (node) {
                is JsonPrimitive -> node.contentOrNull?.let(::clean)
                is JsonObject -> node.string("name")
                else -> null
            }
        }.joinToString(", ")
        else -> ""
    }

    /** A servings count dug out of `recipeYield` ("4 servings", "Serves 6"), else the nutrition serving size. */
    private fun servings(obj: JsonObject): Int? {
        firstNumber(obj["recipeYield"])?.let { return it }
        return firstNumber((obj["nutrition"] as? JsonObject)?.get("servingSize"))
    }

    private fun firstNumber(element: JsonElement?): Int? {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
        val digits = text.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    /** `recipeInstructions`: objects with `text` (HowToStep), plain strings, a single string, or nested lists. */
    private fun directions(obj: JsonObject): List<Direction> {
        val texts = mutableListOf<String>()

        fun collect(element: JsonElement?) {
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let(::clean)?.takeIf { it.isNotBlank() }?.let { texts += it }
                is JsonArray -> element.forEach { collect(it) }
                is JsonObject -> {
                    // HowToSection groups steps under `itemListElement`; HowToStep carries `text`.
                    val nested = element["itemListElement"]
                    if (nested != null) collect(nested)
                    else element.string("text")?.takeIf { it.isNotBlank() }?.let { texts += it }
                }
                else -> {}
            }
        }
        collect(obj["recipeInstructions"])
        return texts.take(Limits.MAX_ARRAY_ITEMS).map { Direction(id = newId(), isHeading = false, text = it) }
    }

    private fun preparationTimes(obj: JsonObject): List<PreparationTime> = buildList {
        for ((key, label) in listOf("prepTime" to "Prep", "cookTime" to "Cook", "totalTime" to "Total")) {
            val raw = obj.string(key) ?: continue
            add(PreparationTime(id = newId(), type = label, timeString = formatDuration(raw)))
        }
    }

    /** `image` may be a URL string, an ImageObject with `url`, or an array of either; take the first. */
    private fun imageUrl(obj: JsonObject): String? = when (val image = obj["image"]) {
        is JsonPrimitive -> image.contentOrNull
        is JsonObject -> image.string("url")
        is JsonArray -> image.firstNotNullOfOrNull { node ->
            when (node) {
                is JsonPrimitive -> node.contentOrNull
                is JsonObject -> node.string("url")
                else -> null
            }
        }
        else -> null
    }

    /**
     * ISO-8601 durations ("PT15M", "PT1H30M") rendered the way the Swift importer renders them, so the same
     * page yields the same "1 hr 30 min" on both platforms. Anything else passes through unchanged.
     */
    fun formatDuration(duration: String): String {
        val d = duration.uppercase()
        if (!d.startsWith("PT")) return duration
        val body = d.removePrefix("PT")
        val hours = body.substringBefore('H', "").takeIf { body.contains('H') }?.toIntOrNull()
        val minutes = body.substringAfter('H', body).substringBefore('M', "")
            .takeIf { body.contains('M') }?.toIntOrNull()
        return listOfNotNull(hours?.let { "$it hr" }, minutes?.let { "$it min" }).joinToString(" ")
            .ifEmpty { duration }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let(::clean)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::clean) }
            .filter { it.isNotBlank() }
            .take(Limits.MAX_ARRAY_ITEMS)

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /**
     * Decodes the HTML character references that survive into JSON-LD, trims, and clamps the length so
     * one oversized field from an untrusted page can't blow up the editor.
     *
     * See [HtmlEntities] for why this is one real pass over the text rather than the chain of `replace`
     * calls it used to be: the chain could only decode the entities it listed, and every recipe plugin
     * writes its fractions as `&#8531;`.
     *
     * A decoded `&nbsp;` becomes an ordinary space rather than U+00A0 — an invisible character that a
     * search for "1 cup" would not match is not what the page meant to say — and the trim comes AFTER
     * decoding, so a field that is nothing but `&nbsp;` ends up empty rather than blank-looking.
     */
    private fun clean(raw: String): String {
        val decoded = HtmlEntities.decode(raw).replace('\u00A0', ' ').trim()
        return if (decoded.length > Limits.MAX_FIELD_LENGTH) decoded.take(Limits.MAX_FIELD_LENGTH) else decoded
    }
}
