package com.enuvro.saltykmp.export

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.importer.SchemaOrgRecipeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeExportTest {

    private fun recipe(
        name: String = "Buttermilk Pancakes",
        block: ServerRecipe.() -> ServerRecipe = { this },
    ): ServerRecipe = ServerRecipe(id = "r1", name = name).block()

    private val fullRecipe = ServerRecipe(
        id = "r1",
        name = "Buttermilk Pancakes",
        createdDate = "2026-08-25T12:34:56.789Z",
        lastModifiedDate = "2026-08-26T01:02:03.004Z",
        lastPrepared = "2026-08-20T17:00:00.000Z",
        source = "Grandma",
        sourceDetails = "https://example.com/pancakes",
        introduction = "Fluffy and quick.",
        difficulty = 2,
        rating = 5,
        isFavorite = true,
        wantToMake = false,
        yield = "12 pancakes",
        servings = 4,
        directions = listOf(
            Direction(id = "d0", isHeading = true, text = "Batter"),
            Direction(id = "d1", text = "Whisk the dry ingredients."),
            Direction(id = "d2", text = "Fold in the buttermilk."),
        ),
        ingredients = listOf(
            Ingredient(id = "i0", isHeading = true, text = "For the batter"),
            Ingredient(id = "i1", isMain = true, text = "2 cups flour"),
            Ingredient(id = "i2", text = "1 tsp salt"),
        ),
        notes = listOf(Note(id = "n1", title = "Tip", content = "Rest the batter.")),
        variations = listOf(Variation(id = "v1", variationName = "Blueberry", text = "Add a cup.")),
        preparationTimes = listOf(
            PreparationTime(id = "p1", type = "Prep", timeString = "15 min"),
            PreparationTime(id = "p2", type = "Cook", timeString = "1 hr 30 min"),
        ),
        nutrition = NutritionInformation(id = "nu1", calories = 210.0, protein = 6.5, sodium = 430.0),
    )

    private val context = RecipeExportContext(
        courseName = "Breakfast",
        categoryNames = listOf("Baking", "Brunch"),
        tagNames = listOf("quick"),
    )

    // ---- .saltyRecipe ----

    @Test
    fun saltyExportDropsFractionalSecondsSoSwiftCanDecodeIt() {
        val export = saltyRecipeExport(fullRecipe)
        assertEquals("2026-08-25T12:34:56Z", export.createdDate)
        assertEquals("2026-08-26T01:02:03Z", export.lastModifiedDate)
        assertEquals("2026-08-20T17:00:00Z", export.lastPrepared)
    }

    @Test
    fun saltyExportSurvivesAnUnparseableDateRatherThanEmittingIt() {
        val export = saltyRecipeExport(recipe { copy(createdDate = "not a date") })
        assertEquals(null, export.createdDate)
    }

    @Test
    fun saltyExportCarriesLibraryNamesNotIds() {
        val export = saltyRecipeExport(fullRecipe, context)
        assertEquals("Breakfast", export.course)
        assertEquals(listOf("Baking", "Brunch"), export.categories)
        assertEquals(listOf("quick"), export.tags)
    }

    @Test
    fun saltyExportDropsRowIdsFromNestedLists() {
        val export = saltyRecipeExport(fullRecipe)
        assertEquals(listOf("Batter", "Whisk the dry ingredients.", "Fold in the buttermilk."), export.directions.map { it.text })
        assertEquals(true, export.directions[0].isHeading)
        assertEquals(null, export.directions[1].isHeading)
        assertEquals(listOf("Prep", "Cook"), export.preparationTimes.map { it.type })
        assertEquals(listOf("Blueberry"), export.variations?.map { it.variationName })
    }

    @Test
    fun ingredientFlagsAreOmittedWhenFalse() {
        val export = saltyRecipeExport(fullRecipe)
        assertEquals(true, export.ingredients[0].isHeading)
        assertEquals(null, export.ingredients[0].isMain)
        assertEquals(true, export.ingredients[1].isMain)
        assertEquals(null, export.ingredients[1].isHeading)
        assertEquals(null, export.ingredients[2].isMain)
    }

    @Test
    fun blankTextFieldsBecomeAbsentRatherThanEmptyStrings() {
        val export = saltyRecipeExport(recipe { copy(source = "", introduction = "   ", yield = "") })
        assertEquals(null, export.source)
        assertEquals(null, export.introduction)
        assertEquals(null, export.yield)
    }

    @Test
    fun jsonOmitsNullsButKeepsDefaults() {
        val json = saltyRecipeExport(recipe()).toJsonString()
        // Swift's encoder writes non-nil defaults and skips nil optionals; ours must agree or the
        // Mac app's decoder sees a null where it expects a value.
        assertTrue(json.contains("\"version\": \"1.0\""), json)
        assertTrue(json.contains("\"difficulty\": 0"), json)
        assertTrue(json.contains("\"rating\": 0"), json)
        assertTrue(json.contains("\"isFavorite\": false"), json)
        assertTrue(json.contains("\"wantToMake\": false"), json)
        assertFalse(json.contains("null"), json)
    }

    @Test
    fun imageBytesTravelAsBase64() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4)
        val export = saltyRecipeExport(recipe(), RecipeExportContext(imageData = bytes))
        assertEquals("AAECAwQ=", export.imageData)
    }

    @Test
    fun anEmptyImageIsNoImage() {
        val export = saltyRecipeExport(recipe(), RecipeExportContext(imageData = ByteArray(0)))
        assertEquals(null, export.imageData)
    }

    // ---- plain text ----

    @Test
    fun plainTextNumbersOnlyRealSteps() {
        val text = RecipeExport.render(fullRecipe, RecipeExportFormat.TEXT, context)
        assertTrue(text.contains("1. Whisk the dry ingredients."), text)
        assertTrue(text.contains("2. Fold in the buttermilk."), text)
        // The heading is printed, but doesn't consume a number.
        assertTrue(text.contains("\nBatter\n"), text)
    }

    @Test
    fun plainTextUnderlinesTheNameAndListsTheParts() {
        val text = RecipeExport.render(fullRecipe, RecipeExportFormat.TEXT, context)
        assertTrue(text.startsWith("Buttermilk Pancakes\n===================\n\n"), text)
        assertTrue(text.contains("Source: Grandma"), text)
        assertTrue(text.contains("Yield: 12 pancakes"), text)
        assertTrue(text.contains("Servings: 4"), text)
        assertTrue(text.contains("• 2 cups flour"), text)
        assertTrue(text.contains("• Prep: 15 min"), text)
        assertTrue(text.contains("• Tip: Rest the batter."), text)
        assertTrue(text.contains("• Blueberry: Add a cup."), text)
    }

    @Test
    fun plainTextNeverCarriesTheImage() {
        val text = RecipeExport.render(
            fullRecipe,
            RecipeExportFormat.TEXT,
            context.copy(imageData = byteArrayOf(1, 2, 3)),
        )
        assertFalse(text.contains("AQID"), text)
    }

    // ---- JSON-LD ----

    @Test
    fun jsonLdMapsTheSchemaOrgFields() {
        val doc = SchemaOrgRecipeJsonLdExporter.render(fullRecipe, context)
        assertTrue(doc.contains("\"@context\": \"https://schema.org\""), doc)
        assertTrue(doc.contains("\"@type\": \"Recipe\""), doc)
        assertTrue(doc.contains("\"name\": \"Buttermilk Pancakes\""), doc)
        assertTrue(doc.contains("\"description\": \"Fluffy and quick.\""), doc)
        assertTrue(doc.contains("\"recipeCategory\": \"Breakfast\""), doc)
        assertTrue(doc.contains("\"keywords\": \"quick, Baking, Brunch\""), doc)
        assertTrue(doc.contains("\"datePublished\": \"2026-08-25T12:34:56Z\""), doc)
        assertTrue(doc.contains("\"recipeYield\": \"12 pancakes\""), doc)
    }

    @Test
    fun jsonLdDropsHeadingsWhichItCannotRepresent() {
        val obj = SchemaOrgRecipeJsonLdExporter.jsonObject(fullRecipe, context)
        val ingredients = obj["recipeIngredient"].toString()
        assertFalse(ingredients.contains("For the batter"), ingredients)
        val instructions = obj["recipeInstructions"].toString()
        assertFalse(instructions.contains("Batter"), instructions)
        assertTrue(instructions.contains("HowToStep"), instructions)
    }

    @Test
    fun jsonLdConvertsPreparationTimesToIsoDurations() {
        val doc = SchemaOrgRecipeJsonLdExporter.render(fullRecipe, context)
        assertTrue(doc.contains("\"prepTime\": \"PT15M\""), doc)
        assertTrue(doc.contains("\"cookTime\": \"PT1H30M\""), doc)
    }

    @Test
    fun jsonLdOmitsAPreparationTimeItCannotParse() {
        val r = recipe {
            copy(preparationTimes = listOf(PreparationTime(id = "p", type = "Prep", timeString = "a while")))
        }
        assertFalse(SchemaOrgRecipeJsonLdExporter.render(r).contains("prepTime"))
    }

    @Test
    fun jsonLdEmitsNutritionWithUnitSuffixes() {
        val doc = SchemaOrgRecipeJsonLdExporter.render(fullRecipe, context)
        assertTrue(doc.contains("\"calories\": \"210 calories\""), doc)
        assertTrue(doc.contains("\"proteinContent\": \"6.5 g\""), doc)
        assertTrue(doc.contains("\"sodiumContent\": \"430 mg\""), doc)
    }

    @Test
    fun jsonLdSkipsNutritionThatHoldsNoValues() {
        val r = recipe { copy(nutrition = NutritionInformation(id = "nu")) }
        assertFalse(SchemaOrgRecipeJsonLdExporter.render(r).contains("nutrition"))
    }

    @Test
    fun jsonLdEmitsUrlOnlyWhenSourceDetailsIsOne() {
        assertTrue(SchemaOrgRecipeJsonLdExporter.render(fullRecipe).contains("\"url\""))
        val r = recipe { copy(sourceDetails = "p. 12") }
        assertFalse(SchemaOrgRecipeJsonLdExporter.render(r).contains("\"url\""))
    }

    @Test
    fun jsonLdFallsBackToServingsForYield() {
        val r = recipe { copy(yield = null, servings = 6) }
        assertTrue(SchemaOrgRecipeJsonLdExporter.render(r).contains("\"recipeYield\": \"6\""))
    }

    @Test
    fun jsonLdSortsKeysTheWaySwiftDoes() {
        val doc = SchemaOrgRecipeJsonLdExporter.render(fullRecipe, context)
        val keys = Regex("""^ {2}"([^"]+)":""", RegexOption.MULTILINE)
            .findAll(doc).map { it.groupValues[1] }.toList()
        assertEquals(keys.sorted(), keys, doc)
    }

    @Test
    fun jsonLdWeWriteIsJsonLdWeCanReadBack() {
        // Export and import are meant to be inverses (the Swift pair is), so the exporter's output is fed
        // straight back through the app's own importer. This is what catches a field renamed on one side.
        val doc = SchemaOrgRecipeJsonLdExporter.render(fullRecipe, context)
        val parsed = SchemaOrgRecipeParser
            .parse("""<script type="application/ld+json">$doc</script>""")
            .single()

        assertEquals("Buttermilk Pancakes", parsed.name)
        assertEquals("Grandma", parsed.source)
        assertEquals("https://example.com/pancakes", parsed.sourceDetails)
        assertEquals("Fluffy and quick.", parsed.introduction)
        assertEquals("12 pancakes", parsed.yield)
        // Headings don't survive the trip — schema.org has nowhere to put them — so the two real
        // ingredient lines and the two real steps are what should come back.
        assertEquals(listOf("2 cups flour", "1 tsp salt"), parsed.ingredients.map { it.text })
        assertEquals(
            listOf("Whisk the dry ingredients.", "Fold in the buttermilk."),
            parsed.directions.map { it.text },
        )
        // The durations survive as the human-readable strings the exporter built them from.
        assertEquals(
            listOf("Prep" to "15 min", "Cook" to "1 hr 30 min"),
            parsed.preparationTimes.filter { it.type in listOf("Prep", "Cook") }.map { it.type to it.timeString },
        )
    }

    // ---- filenames ----

    @Test
    fun filenameStemStripsWhatAFilesystemWouldReject() {
        assertEquals("Mac n Cheese", RecipeExport.filenameStem("Mac / n \\ Cheese"))
        assertEquals("Soup", RecipeExport.filenameStem("  Soup  "))
        assertEquals("A B", RecipeExport.filenameStem("A\n\nB"))
        assertEquals("Recipe", RecipeExport.filenameStem("   "))
        assertEquals("Recipe", RecipeExport.filenameStem("..."))
        assertEquals("Recipe", RecipeExport.filenameStem(""))
    }

    @Test
    fun filenameStemIsBounded() {
        assertEquals(80, RecipeExport.filenameStem("x".repeat(500)).length)
    }
}
