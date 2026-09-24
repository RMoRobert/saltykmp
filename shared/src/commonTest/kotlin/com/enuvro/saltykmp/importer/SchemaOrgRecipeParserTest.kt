package com.enuvro.saltykmp.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shapes recipe pages publish JSON-LD in are pinned by the shared corpus — salty-contract's `webimport`
 * suite, run by `ContractCorpusTest` here and by the Swift core's runner, from SPEC.md §8. What stays here is
 * what the corpus cannot pin: a draft's fresh ids (WEB-031), which are random, and the bare-JSON-LD entry
 * point, which is this API's rather than the contract's. Unlike the corpus runner, this runs on every target.
 */
class SchemaOrgRecipeParserTest {

    @Test fun mintsFreshIdsForEveryRow() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","@id":"https://example.com/#r","name":"R",""" +
            """"recipeIngredient":["1 egg"],"recipeInstructions":["Beat it"],"nutrition":{"calories":"100 kcal"}}</script>"""
        val recipe = SchemaOrgRecipeParser.parse(html).single()

        val ingredient = recipe.ingredients.single()
        val direction = recipe.directions.single()
        assertTrue(ingredient.id.isNotEmpty() && direction.id.isNotEmpty())
        assertNotEquals(ingredient.id, direction.id)
        assertNotEquals("https://example.com/#r", ingredient.id, "nothing from the page becomes an id")
        assertTrue(recipe.nutrition?.id?.isNotEmpty() == true)
    }

    @Test fun parsesBareJsonLdWithNoPageAroundIt() {
        val recipes = SchemaOrgRecipeParser.parseJsonLd("""{"@type":"Recipe","name":"Bare","image":"b.jpg"}""", "https://example.com/r/")
        assertEquals(listOf("Bare"), recipes.map { it.name })
        assertEquals("https://example.com/r/b.jpg", recipes.single().imageUrl, "relative addresses resolve against the page here too")
    }
}
