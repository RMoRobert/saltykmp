package com.enuvro.saltykmp.importer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The regex-heavy web-import rules, run on EVERY target. The shared corpus pins these (salty-contract
 * SPEC.md §8) but only runs on the JVM, and Kotlin/Native has its own regex engine — so the patterns most
 * likely to differ between engines (Unicode dashes and micro signs, case-insensitive matching, anchors,
 * fractions) are checked here too, on iOS as well as the JVM.
 */
class SchemaOrgRegexRulesTest {

    @Test fun durations() {
        val cases = mapOf(
            "PT1H30M" to "1 hr 30 min", "PT90M" to "1 hr 30 min", "pt45m" to "45 min", "P0Y0M0DT0H50M0.000S" to "50 min",
            "P1DT2H" to "1 day 2 hr", "PT1,5H" to "1 hr 30 min", "PT45S" to "45 sec", "PT90S" to "2 min", "PT0S" to "0 min",
            "P1M" to "P1M", "PT" to "PT", "about an hour" to "about an hour",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, SchemaOrgDuration.display(input), input) }
    }

    @Test fun nutritionAmounts() {
        assertEquals(64.0, NutritionAmount.parse("64g", NutritionAmount.Unit.GRAMS))
        assertEquals(1299.7, NutritionAmount.parse("1,299.7 mg", NutritionAmount.Unit.MILLIGRAMS))
        assertEquals(300.0, NutritionAmount.parse("0.3 g", NutritionAmount.Unit.MILLIGRAMS))
        assertEquals(5.0, NutritionAmount.parse("5 µg", NutritionAmount.Unit.MICROGRAMS))
        assertEquals(5.0, NutritionAmount.parse("5 μg", NutritionAmount.Unit.MICROGRAMS))
        assertEquals(239.0057, NutritionAmount.parse("1000 KJ", NutritionAmount.Unit.KILOCALORIES))
        assertEquals(9.0, NutritionAmount.parse("9 grams of protein", NutritionAmount.Unit.GRAMS))
        assertEquals(440.0, NutritionAmount.parse("440", NutritionAmount.Unit.KILOCALORIES))
        assertEquals(null, NutritionAmount.parse("12 %", NutritionAmount.Unit.MILLIGRAMS))
        assertEquals(null, NutritionAmount.parse("12 carbs", NutritionAmount.Unit.GRAMS))
    }

    @Test fun statedServings() {
        val cases = mapOf(
            "4 to 6 servings" to 4, "4–6 servings" to 4, "Serves 4-6" to 4, "SERVES 6" to 6, "for 2 people" to 2,
            "1 of 8 servings" to 8, "1 9\" pie (8 servings)" to 8, "12 cookies" to null, "Serves 123456789012345678901234567890" to null,
        )
        cases.forEach { (input, expected) -> assertEquals(expected, SchemaOrgYield.statedServings(input), input) }
    }

    @Test fun scriptTypesAndAddresses() {
        val page = """<script data-x="1" type='Application/LD+JSON; charset=utf-8' >{"@type":"Recipe","name":"Q","image":"../img/q.jpg"}</script>""" +
            """<script type="application/ld+json-x">{"@type":"Recipe","name":"X"}</script>"""
        val recipes = SchemaOrgRecipeParser.parse(page, "https://example.com/recipes/q/")
        assertEquals(listOf("Q"), recipes.map { it.name })
        assertEquals("https://example.com/recipes/img/q.jpg", recipes.single().imageUrl)
    }
}
