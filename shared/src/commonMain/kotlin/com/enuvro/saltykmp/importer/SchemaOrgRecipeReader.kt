package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.util.newId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One schema.org `Recipe` node, read into a draft recipe field by field, following salty-contract SPEC.md
 * §8.2–8.3 (WEB-010 to WEB-031). Mirrors SaltyCore's `SchemaOrgRecipeReader`.
 *
 * [pageUrl] is where the page came from, when that is an http(s) address: the base for relative addresses
 * (WEB-011) and the source of a recipe that declares no `url` (WEB-020).
 */
internal class SchemaOrgRecipeReader(private val dataset: JsonLdDataset, pageUrl: String?) {

    private val pageUrl: String? = pageUrl?.takeIf(WebAddress::isHttpAddress)

    /** The recipe [node] describes: a draft with fresh ids, nothing stored (WEB-031). */
    fun recipe(node: JsonObject): ParsedRecipe {
        val yield = yieldValues(node)
        val nutritionNode = dataset.values(node, "nutrition").firstOrNull { it is JsonObject }
        return ParsedRecipe(
            name = text(node, "name").orEmpty(),
            source = author(node),
            sourceDetails = WebAddress.httpAddress(text(node, "url"), pageUrl) ?: pageUrl.orEmpty(),
            introduction = text(node, "description").orEmpty(),
            yield = yield.text,
            servings = yield.servings(nutritionNode?.let { text(it, "servingSize") }),
            ingredients = ingredients(node),
            directions = directions(node),
            preparationTimes = preparationTimes(node),
            nutrition = nutritionNode?.let(::nutrition),
            imageUrl = imageUrl(node),
        )
    }

    // ---- text ----

    /** WEB-010: the first of the property's values that is non-empty text after cleaning. */
    private fun text(node: JsonElement, property: String): String? =
        dataset.values(node, property).firstNotNullOfOrNull { value -> textOf(value)?.takeIf { it.isNotEmpty() } }

    /** A string, or a bare number in its shortest decimal form (WEB-L03), cleaned; null for anything else. */
    private fun textOf(value: JsonElement): String? {
        if (value !is JsonPrimitive || value === JsonNull) return null
        if (value.isString) return clean(value.content)
        val number = value.content.toDoubleOrNull() ?: return null
        return clean(decimalText(number))
    }

    /**
     * WEB-L02 and WEB-008: character references decoded once (strictly), whitespace trimmed after decoding,
     * and the result clamped.
     */
    private fun clean(raw: String): String {
        val decoded = HtmlEntities.decode(raw).replace(' ', ' ').trim()
        return if (decoded.length > MAX_FIELD_LENGTH) decoded.take(MAX_FIELD_LENGTH) else decoded
    }

    // ---- fields ----

    /** WEB-021: a Person or Organization by its name, or text; several joined in order. */
    private fun author(node: JsonObject): String =
        dataset.values(node, "author")
            .mapNotNull { if (it is JsonObject) text(it, "name") else textOf(it) }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    /** WEB-022: the first value that yields an address — a URL, or a MediaObject's `contentUrl`, else `url`. */
    private fun imageUrl(node: JsonObject): String? =
        dataset.values(node, "image").firstNotNullOfOrNull { value ->
            if (value is JsonObject) {
                WebAddress.httpAddress(text(value, "contentUrl"), pageUrl) ?: WebAddress.httpAddress(text(value, "url"), pageUrl)
            } else {
                WebAddress.httpAddress(textOf(value), pageUrl)
            }
        }

    /** WEB-023: `recipeYield`, else the inherited HowTo `yield`. */
    private fun yieldValues(node: JsonObject): SchemaOrgYield {
        val property = if (dataset.has(node, "recipeYield")) "recipeYield" else "yield"
        return SchemaOrgYield(dataset.values(node, property).mapNotNull(::yieldValue))
    }

    private fun yieldValue(value: JsonElement): SchemaOrgYield.Value? {
        if (value is JsonObject) {
            val unit = text(value, "unitText").orEmpty()
            text(value, "value")?.let { amount ->
                val counts = unit.isEmpty() || SchemaOrgYield.isServingsUnit(unit)
                return SchemaOrgYield.Value(
                    text = if (unit.isEmpty()) amount else "$amount $unit",
                    isNumber = unit.isEmpty() && amount.toDoubleOrNull() != null,
                    servingsCount = if (counts) wholeCount(amount.toDoubleOrNull()) else null,
                )
            }
            val low = text(value, "minValue")
            val high = text(value, "maxValue")
            if (low != null && high != null) {
                val range = "$low–$high"
                return SchemaOrgYield.Value(if (unit.isEmpty()) range else "$range $unit", isNumber = false, servingsCount = null)
            }
            return null
        }
        val text = textOf(value)?.takeIf { it.isNotEmpty() } ?: return null
        val number = text.toDoubleOrNull()
        val isNumber = number != null && text.all { it == '.' || it in '0'..'9' }
        return SchemaOrgYield.Value(text, isNumber, if (isNumber) wholeCount(number) else null)
    }

    private fun wholeCount(number: Double?): Int? =
        number?.takeIf { it > 0 && it == kotlin.math.floor(it) && it < Int.MAX_VALUE }?.toInt()

    /** WEB-025: `recipeIngredient`, else the superseded `ingredients`. */
    private fun ingredients(node: JsonObject): List<Ingredient> {
        val property = if (dataset.has(node, "recipeIngredient")) "recipeIngredient" else "ingredients"
        return dataset.values(node, property)
            .flatMap(::ingredientTexts)
            .filter { it.isNotEmpty() }
            .take(MAX_LIST_ITEMS)
            .map { Ingredient(id = newId(), isHeading = false, isMain = false, text = it) }
    }

    private fun ingredientTexts(value: JsonElement): List<String> {
        if (value !is JsonObject) return listOfNotNull(textOf(value))
        if (dataset.has(value, "itemListElement")) return dataset.values(value, "itemListElement").flatMap(::ingredientTexts)
        if (dataset.hasType(value, "ListItem") || dataset.has(value, "item")) {
            val items = dataset.values(value, "item")
            return if (items.isEmpty()) listOfNotNull(text(value, "name")) else items.flatMap(::ingredientTexts)
        }
        if (dataset.hasType(value, "PropertyValue") || dataset.has(value, "value")) {
            val unit = text(value, "unitText") ?: text(value, "unitCode")?.let { UNIT_CODES[it.uppercase()] }
            return listOf(listOfNotNull(text(value, "value"), unit, text(value, "name")).filter { it.isNotEmpty() }.joinToString(" "))
        }
        return listOfNotNull(text(value, "name"))
    }

    /** WEB-026: `recipeInstructions`, else the inherited HowTo `step`. Sections become heading rows. */
    private fun directions(node: JsonObject): List<Direction> {
        val property = if (dataset.has(node, "recipeInstructions")) "recipeInstructions" else "step"
        val rows = mutableListOf<Direction>()

        fun add(text: String?, heading: Boolean) {
            if (rows.size >= MAX_LIST_ITEMS || text.isNullOrEmpty()) return
            rows += Direction(id = newId(), isHeading = heading, text = text)
        }

        fun walk(items: List<JsonElement>) {
            fun visit(value: JsonElement) {
                when {
                    value is JsonArray -> walk(value)
                    value !is JsonObject -> add(textOf(value), heading = false)
                    dataset.hasType(value, "HowToSection") -> {
                        add(text(value, "name"), heading = true)
                        walk(dataset.values(value, "itemListElement"))
                    }
                    dataset.hasType(value, "HowToStep") -> {
                        val parts = dataset.values(value, "itemListElement")
                        if (parts.isEmpty()) add(text(value, "text") ?: text(value, "name"), heading = false) else walk(parts)
                    }
                    dataset.has(value, "itemListElement") -> walk(dataset.values(value, "itemListElement"))
                    dataset.hasType(value, "ListItem") || dataset.has(value, "item") -> {
                        val items = dataset.values(value, "item")
                        if (items.isEmpty()) add(text(value, "text") ?: text(value, "name"), heading = false) else walk(items)
                    }
                    else -> add(text(value, "text") ?: text(value, "name"), heading = false)
                }
            }
            inPositionOrder(items).forEach { visit(it) }
        }

        walk(dataset.values(node, property))
        return rows
    }

    /**
     * When every item carries a numeric `position`, those items in that order (stable); otherwise as written.
     * JSON-LD arrays are formally unordered, and `position` is how a list says its order.
     */
    private fun inPositionOrder(items: List<JsonElement>): List<JsonElement> {
        val positions = items.map { item ->
            if (item !is JsonObject) null
            else dataset.values(item, "position").firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p !== JsonNull }?.content?.toDoubleOrNull() }
        }
        if (items.isEmpty() || positions.any { it == null }) return items
        return items.zip(positions).sortedBy { it.second }.map { it.first }
    }

    /** WEB-027 and WEB-028. */
    private fun preparationTimes(node: JsonObject): List<PreparationTime> {
        val cook = if (dataset.has(node, "cookTime")) "cookTime" else "performTime"
        return listOf("prepTime" to "Prep", cook to "Cook", "totalTime" to "Total").mapNotNull { (property, label) ->
            text(node, property)?.let { PreparationTime(id = newId(), type = label, timeString = SchemaOrgDuration.display(it)) }
        }
    }

    /** WEB-029: each field in the unit Salty stores it in; null when nothing in the block is usable. */
    private fun nutrition(block: JsonElement): NutritionInformation? {
        fun amount(property: String, unit: NutritionAmount.Unit): Double? =
            dataset.values(block, property).firstNotNullOfOrNull { value ->
                if (value is JsonPrimitive && value.isString) NutritionAmount.parse(clean(value.content), unit)
                else NutritionAmount.value(value, unit)
            }

        val information = NutritionInformation(
            id = newId(),
            servingSize = text(block, "servingSize"),
            calories = amount("calories", NutritionAmount.Unit.KILOCALORIES),
            protein = amount("proteinContent", NutritionAmount.Unit.GRAMS),
            carbohydrates = amount("carbohydrateContent", NutritionAmount.Unit.GRAMS),
            fat = amount("fatContent", NutritionAmount.Unit.GRAMS),
            saturatedFat = amount("saturatedFatContent", NutritionAmount.Unit.GRAMS),
            transFat = amount("transFatContent", NutritionAmount.Unit.GRAMS),
            fiber = amount("fiberContent", NutritionAmount.Unit.GRAMS),
            sugar = amount("sugarContent", NutritionAmount.Unit.GRAMS),
            sodium = amount("sodiumContent", NutritionAmount.Unit.MILLIGRAMS),
            cholesterol = amount("cholesterolContent", NutritionAmount.Unit.MILLIGRAMS),
            // Not schema.org; read because Salty has the fields and a page that publishes them means them.
            vitaminD = amount("vitaminDContent", NutritionAmount.Unit.MICROGRAMS),
            calcium = amount("calciumContent", NutritionAmount.Unit.MILLIGRAMS),
            iron = amount("ironContent", NutritionAmount.Unit.MILLIGRAMS),
            potassium = amount("potassiumContent", NutritionAmount.Unit.MILLIGRAMS),
            vitaminA = amount("vitaminAContent", NutritionAmount.Unit.MICROGRAMS),
            vitaminC = amount("vitaminCContent", NutritionAmount.Unit.MILLIGRAMS),
        )
        // schema.org's unsaturatedFatContent has no Salty field to go in; see SPEC.md WEB-029's known gap.
        return information.takeIf { it != NutritionInformation(id = it.id) }
    }

    private companion object {
        /** WEB-008. */
        const val MAX_FIELD_LENGTH = 20_000
        const val MAX_LIST_ITEMS = 1_000

        /** The UN/CEFACT common codes a kitchen measure is likely to use (WEB-025). */
        val UNIT_CODES = mapOf(
            "G21" to "cup", "G24" to "tablespoon", "G25" to "teaspoon", "GRM" to "g", "KGM" to "kg",
            "MLT" to "ml", "LTR" to "l", "ONZ" to "oz", "LBR" to "lb",
        )

        /** [number] as a person would write it: no fractional part when it is whole ("4", not "4.0"). */
        fun decimalText(number: Double): String =
            if (number == kotlin.math.floor(number) && kotlin.math.abs(number) < 1e15) number.toLong().toString()
            else number.toString()
    }
}
