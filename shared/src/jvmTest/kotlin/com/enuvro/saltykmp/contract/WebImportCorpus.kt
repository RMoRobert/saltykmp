package com.enuvro.saltykmp.contract

import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.importer.ParsedRecipe
import com.enuvro.saltykmp.importer.SchemaOrgRecipeParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The corpus's `webimport` suite (salty-contract SPEC.md §8), op `scan_web_recipes`: build the page a case
 * describes, read it with [SchemaOrgRecipeParser], and compare the fields the case names. The expectation
 * format is described in the suite's own `description`.
 */
internal fun assertWebImport(c: CorpusCase) {
    val input = c.input.jsonObject
    val pageUrl = input["page_url"]?.jsonPrimitive?.contentOrNull
    val scanned = SchemaOrgRecipeParser.parse(page(c, input), pageUrl)
    val expected = c.expect.jsonObject.getValue("recipes").jsonArray

    assertEquals(expected.size, scanned.size, "${c.because}\n  found ${scanned.map { it.name }}")
    scanned.zip(expected).forEachIndexed { index, (actual, want) ->
        compare(actual, want.jsonObject, "${c.because}\n  [recipe $index]")
    }
}

/** `html` verbatim, or each of `blocks` (raw text) / the one `jsonld` value as a script in a minimal page. */
private fun page(c: CorpusCase, input: JsonObject): String {
    input["html"]?.let { return it.jsonPrimitive.content }
    val blocks = input["blocks"]?.jsonArray?.map { it.jsonPrimitive.content }
        ?: listOf(input["jsonld"]?.toString() ?: fail("${c.id}: input has none of html, blocks, jsonld"))
    return "<html><head>" + blocks.joinToString("") { "<script type=\"application/ld+json\">$it</script>" } +
        "</head><body></body></html>"
}

private fun compare(actual: ParsedRecipe, want: JsonObject, because: String) {
    for ((key, value) in want) {
        val label = "$because [$key]"
        when (key) {
            "name" -> assertEquals(value.text(), actual.name, label)
            "introduction" -> assertEquals(value.text(), actual.introduction, label)
            "source" -> assertEquals(value.text(), actual.source, label)
            "source_details" -> assertEquals(value.text(), actual.sourceDetails, label)
            "yield" -> assertEquals(value.text(), actual.yield, label)
            "servings" -> assertEquals(if (value is JsonNull) null else value.jsonPrimitive.int, actual.servings, label)
            "image_url" -> assertEquals(value.text(), actual.imageUrl, label)
            "ingredients" -> assertEquals(value.jsonArray.map { it.jsonPrimitive.content }, actual.ingredients.map { it.text }, label)
            "ingredient_count" -> assertEquals(value.jsonPrimitive.int, actual.ingredients.size, label)
            "direction_count" -> assertEquals(value.jsonPrimitive.int, actual.directions.size, label)
            // A heading and a step with the same words are different rows, so they are told apart here.
            "directions" -> assertEquals(
                value.jsonArray.map { row ->
                    if (row is JsonObject) "[heading] " + row.getValue("heading").jsonPrimitive.content else row.jsonPrimitive.content
                },
                actual.directions.map { if (it.isHeading == true) "[heading] ${it.text}" else it.text },
                label,
            )
            "prep_times" -> assertEquals(
                value.jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.content } },
                actual.preparationTimes.map { listOf(it.type, it.timeString) },
                label,
            )
            "nutrition" -> compareNutrition(actual.nutrition, value, label)
            else -> fail("$label: not a field this runner knows. A typo in the corpus, or a new field to map.")
        }
    }
}

/** `null` for none; otherwise every listed field present and equal (within 0.01), and every other field absent. */
private fun compareNutrition(actual: NutritionInformation?, want: JsonElement, label: String) {
    if (want is JsonNull) {
        assertEquals(null, actual, "$label: expected no nutrition")
        return
    }
    actual ?: fail("$label: expected nutrition, got none")
    val fields = want.jsonObject
    val numbers = mapOf(
        "calories" to actual.calories, "carbohydrates" to actual.carbohydrates, "cholesterol" to actual.cholesterol,
        "fat" to actual.fat, "fiber" to actual.fiber, "protein" to actual.protein, "saturated_fat" to actual.saturatedFat,
        "sodium" to actual.sodium, "sugar" to actual.sugar, "trans_fat" to actual.transFat, "vitamin_d" to actual.vitaminD,
        "calcium" to actual.calcium, "iron" to actual.iron, "potassium" to actual.potassium, "vitamin_a" to actual.vitaminA,
        "vitamin_c" to actual.vitaminC,
    )
    (fields.keys - numbers.keys - "serving_size").forEach { fail("$label.$it: not a nutrition field this runner knows") }
    for ((name, value) in numbers) {
        val expected = fields[name]
        if (expected == null) {
            assertEquals(null, value, "$label.$name: expected none")
        } else {
            value ?: fail("$label.$name: expected ${expected.jsonPrimitive.double}, got none")
            if (abs(value - expected.jsonPrimitive.double) > 0.01) fail("$label.$name: expected ${expected.jsonPrimitive.double}, got $value")
        }
    }
    assertEquals(fields["serving_size"]?.text(), actual.servingSize, "$label.serving_size")
}

private fun JsonElement.text(): String? = if (this is JsonNull) null else (this as? JsonPrimitive)?.content
