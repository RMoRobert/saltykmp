package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime

/** A recipe read off a page's JSON-LD: a draft to review, nothing stored. */
data class ParsedRecipe(
    val name: String,
    val source: String = "",
    val sourceDetails: String = "",
    val introduction: String = "",
    val yield: String = "",
    val servings: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    /** Steps, with a `HowToSection`'s name as a heading row before its steps (WEB-026). */
    val directions: List<Direction> = emptyList(),
    val preparationTimes: List<PreparationTime> = emptyList(),
    val nutrition: NutritionInformation? = null,
    /** The recipe photo's address, absolute and http(s). The import flow downloads it separately. */
    val imageUrl: String? = null,
)

/**
 * Reads schema.org `Recipe` data out of the JSON-LD a page publishes, following salty-contract SPEC.md §8:
 * schema.org and JSON-LD 1.1, plus the departures that section names (WEB-L01 to WEB-L03). The `webimport`
 * corpus pins the rules, and SaltyCore's `SchemaOrgRecipeJSONLDImporter` implements the same ones in Swift.
 * Pure and I/O-free: [RecipeWebImporter] and the server's import route do the fetching.
 *
 * There is no HTML parser in the shared module, so `<script>` elements are located by scanning rather than
 * by parsing the document. That is sufficient — only the script bodies are needed, and their contents are
 * parsed as real (strict) JSON; malformed markup around them degrades to "found nothing" rather than
 * throwing. [JsonLdDataset] finds the recipes; [SchemaOrgRecipeReader] reads each one.
 */
object SchemaOrgRecipeParser {

    /** Bounds on untrusted page content (WEB-008). Per-field and per-list limits are the reader's. */
    object Limits {
        const val MAX_INPUT_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_SCRIPT_TAGS = 50
    }

    /**
     * Every recipe on a page, in document order. [pageUrl] is where it came from: relative addresses on the
     * page resolve against it (WEB-011), and a recipe that declares no `url` records it as its source
     * (WEB-020) — plenty of sites, AllRecipes among them, declare none. Only an http(s) address counts.
     */
    fun parse(html: String, pageUrl: String? = null): List<ParsedRecipe> {
        if (html.encodeToByteArray().size > Limits.MAX_INPUT_BYTES) return emptyList()
        // WEB-008 counts every JSON-LD block, readable or not; WEB-L01 skips the unreadable ones.
        return read(jsonLdBlocks(html).mapNotNull(JsonLdDataset::parseBlock), pageUrl)
    }

    /** Every recipe in a bare JSON-LD document — one script block's body, or a `.json`/`.jsonld` file. */
    fun parseJsonLd(json: String, pageUrl: String? = null): List<ParsedRecipe> {
        if (json.encodeToByteArray().size > Limits.MAX_INPUT_BYTES) return emptyList()
        return read(listOfNotNull(JsonLdDataset.parseBlock(json)), pageUrl)
    }

    private fun read(blocks: List<kotlinx.serialization.json.JsonElement>, pageUrl: String?): List<ParsedRecipe> {
        val dataset = JsonLdDataset(blocks)
        val reader = SchemaOrgRecipeReader(dataset, pageUrl)
        return dataset.recipes().map(reader::recipe)
    }

    /**
     * The bodies of the page's JSON-LD script elements (WEB-001): those whose `type` attribute's MIME
     * essence is `application/ld+json`, whatever its case or parameters. At most [Limits.MAX_SCRIPT_TAGS];
     * the first `</script` ends a body. The one shape this misreads is a `>` inside an attribute value of the
     * opening tag, which no real page has on a JSON-LD block.
     */
    private fun jsonLdBlocks(html: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = 0
        while (blocks.size < Limits.MAX_SCRIPT_TAGS) {
            val open = html.indexOf("<script", index, ignoreCase = true)
            if (open < 0) break
            val openEnd = html.indexOf('>', open)
            if (openEnd < 0) break
            val attributes = html.substring(open, openEnd)
            index = openEnd + 1
            val close = html.indexOf("</script", index, ignoreCase = true)
            if (close < 0) break
            if (isJsonLd(attributes)) blocks += html.substring(index, close)
            index = close + 1
        }
        return blocks
    }

    private val typeAttribute = Regex("""\stype\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)

    private fun isJsonLd(attributes: String): Boolean {
        val match = typeAttribute.find(attributes) ?: return false
        val type = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return false
        return type.substringBefore(';').trim().lowercase() == "application/ld+json"
    }
}
