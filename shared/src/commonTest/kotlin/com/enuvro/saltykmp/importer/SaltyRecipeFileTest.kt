package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import com.enuvro.saltykmp.export.RecipeExportContext
import com.enuvro.saltykmp.export.saltyRecipeExport
import com.enuvro.saltykmp.export.toJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaltyRecipeFileTest {

    private fun read(json: String) = SaltyRecipeFile.recipes(json.encodeToByteArray()).toList()

    /** The shape the Swift app writes: whole-second `Z` dates, flags present, absent optionals omitted. */
    private val swiftRecipe = """
        {
          "categories" : [ "Breads" ],
          "course" : "Side",
          "createdDate" : "2025-08-07T20:17:17Z",
          "difficulty" : 2,
          "directions" : [ { "isHeading" : true, "text" : "Dough" }, { "text" : "Knead, then rest." } ],
          "id" : "0198A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B",
          "ingredients" : [ { "isMain" : true, "text" : "500 g flour" }, { "text" : "10 g salt" } ],
          "isFavorite" : true,
          "lastModifiedDate" : "2025-08-11T03:08:54Z",
          "name" : "Focaccia",
          "notes" : [ { "content" : "Use a hot oven.", "id" : "N1", "title" : "Tip" } ],
          "preparationTimes" : [ { "timeString" : "2 hr", "type" : "Rise" } ],
          "rating" : 4,
          "servings" : 8,
          "version" : "1.0",
          "wantToMake" : false
        }
    """.trimIndent()

    @Test
    fun aSingleObjectIsOneRecipe() {
        val recipes = read(swiftRecipe)
        assertEquals(1, recipes.size)
        val r = assertNotNull(recipes.single())
        assertEquals("Focaccia", r.name)
        assertEquals("Side", r.course)
        assertEquals(listOf("Breads"), r.categories)
        assertEquals(true, r.isFavorite)
        assertEquals(2, r.difficulty)
        assertEquals(listOf("Dough", "Knead, then rest."), r.directions.map { it.text })
        assertEquals(true, r.ingredients[0].isMain)
        assertEquals(null, r.variations, "absent, as Swift omits a nil array")
    }

    @Test
    fun anArrayIsSeveralRecipesInFileOrder() {
        val recipes = read("""[ $swiftRecipe , ${swiftRecipe.replace("Focaccia", "Ciabatta")} ]""")
        assertEquals(listOf("Focaccia", "Ciabatta"), recipes.map { it?.name })
    }

    @Test
    fun anEmptyArrayHoldsNoRecipes() {
        assertEquals(emptyList(), read(" [ ] "))
    }

    @Test
    fun aByteOrderMarkAndLeadingWhitespaceAreSkipped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n\t  $swiftRecipe".encodeToByteArray()
        assertEquals(listOf("Focaccia"), SaltyRecipeFile.recipes(bytes).map { it?.name }.toList())
    }

    @Test
    fun keysFromANewerSaltyAreIgnored() {
        val r = read(swiftRecipe.replace("\"version\" : \"1.0\"", "\"version\" : \"2.0\", \"mealPlan\" : { \"day\" : 3 }"))
        assertEquals("Focaccia", r.single()?.name)
    }

    @Test
    fun aNullWhereTheDocumentHasADefaultReadsAsTheDefault() {
        val r = read("""{ "id": "x", "name": "Plain", "isFavorite": null, "directions": null, "difficulty": null }""")
        val recipe = assertNotNull(r.single())
        assertEquals(false, recipe.isFavorite)
        assertEquals(emptyList(), recipe.directions)
        assertEquals(0, recipe.difficulty)
    }

    @Test
    fun aBadEntryCostsOnlyThatRecipe() {
        // The middle entry has no name, which the document requires (as Swift's does).
        val recipes = read("""[ $swiftRecipe, { "id": "no-name" }, ${swiftRecipe.replace("Focaccia", "Ciabatta")} ]""")
        assertEquals(listOf("Focaccia", null, "Ciabatta"), recipes.map { it?.name })
    }

    @Test
    fun bracketsCommasAndEscapesInsideStringsDoNotSplitAnEntry() {
        val tricky = """{ "id": "t", "name": "Tricky [1], {2} \"quoted\" \\", "notes": [ { "id": "n", "title": "a,b", "content": "]}" } ] }"""
        val recipes = read("[$tricky,$tricky]")
        assertEquals(2, recipes.size)
        assertEquals("Tricky [1], {2} \"quoted\" \\", recipes[0]?.name)
        assertEquals("]}", recipes[1]?.notes?.single()?.content)
    }

    @Test
    fun multiByteCharactersSurviveTheSplit() {
        val name = "Baked S’mores Doughnuts — crème brûlée 🍩"
        val recipes = read("""[ { "id": "a", "name": "$name" }, { "id": "b", "name": "Two" } ]""")
        assertEquals(listOf(name, "Two"), recipes.map { it?.name })
    }

    @Test
    fun aTruncatedFileKeepsItsCompleteEntries() {
        val whole = "[ $swiftRecipe, ${swiftRecipe.replace("Focaccia", "Ciabatta")} ]"
        val cut = whole.substring(0, whole.lastIndexOf("Ciabatta"))
        assertEquals(listOf("Focaccia", null), read(cut).map { it?.name })
    }

    @Test
    fun somethingThatIsNotJsonIsRefusedOutright() {
        assertFailsWith<NotASaltyRecipeFileException> { read("Focaccia: flour, water, salt") }
        assertFailsWith<NotASaltyRecipeFileException> { read("   ") }
        assertFailsWith<NotASaltyRecipeFileException> { SaltyRecipeFile.recipes(byteArrayOf()) }
        // A JPEG's first bytes: no scan of the rest is attempted.
        assertFailsWith<NotASaltyRecipeFileException> {
            SaltyRecipeFile.recipes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        }
    }

    @Test
    fun jsonThatIsNotASaltyRecipeReadsAsAnUnreadableEntry() {
        // A schema.org export is JSON, but not this document.
        assertEquals(listOf(null), read("""{ "@context": "https://schema.org", "@type": "Recipe", "name": "Soup" }"""))
    }

    @Test
    fun whatThisAppExportsReadsBackUnchanged() {
        val recipe = ServerRecipe(
            id = "r1",
            name = "Buttermilk Pancakes",
            createdDate = "2026-08-25T12:34:56.789Z",
            lastModifiedDate = "2026-08-26T01:02:03.004Z",
            source = "Grandma",
            introduction = "Fluffy.",
            difficulty = 2,
            rating = 5,
            isFavorite = true,
            yield = "12",
            servings = 4,
            directions = listOf(Direction(id = "d0", isHeading = true, text = "Batter"), Direction(id = "d1", text = "Whisk.")),
            ingredients = listOf(Ingredient(id = "i0", isMain = true, text = "2 cups flour")),
            notes = listOf(Note(id = "n1", title = "Tip", content = "Rest it.")),
            variations = listOf(Variation(id = "v1", variationName = "Blueberry", text = "Add a cup.")),
            preparationTimes = listOf(PreparationTime(id = "p1", type = "Prep", timeString = "15 min")),
            nutrition = NutritionInformation(id = "nu1", calories = 210.0),
        )
        val exported = saltyRecipeExport(
            recipe,
            RecipeExportContext(courseName = "Breakfast", categoryNames = listOf("Brunch"), tagNames = listOf("quick"), imageData = byteArrayOf(1, 2, 3)),
        )
        assertEquals(exported, read(exported.toJsonString()).single())
    }

    @Test
    fun topLevelValuesFindsEachElementOfAnArray() {
        val text = """[ {"a":[1,2,{"b":"]"}]} , 7 ,"s,t" ,[ ] ]"""
        val bytes = text.encodeToByteArray()
        val values = topLevelValues(bytes, 0).map { bytes.decodeToString(it.first, it.last + 1) }
        assertEquals(listOf("""{"a":[1,2,{"b":"]"}]}""", "7", "\"s,t\"", "[ ]"), values)
    }

    @Test
    fun topLevelValuesTreatsAnythingElseAsOneValue() {
        val bytes = """  {"a": 1}  """.encodeToByteArray()
        val values = topLevelValues(bytes, 2).map { bytes.decodeToString(it.first, it.last + 1) }
        assertEquals(listOf("""{"a": 1}"""), values)
        assertNull(topLevelValues(bytes, bytes.size).firstOrNull())
    }
}
