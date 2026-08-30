package com.enuvro.saltykmp.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaOrgRecipeParserTest {

    private fun page(jsonLd: String) =
        """<html><head><script type="application/ld+json">$jsonLd</script></head><body>x</body></html>"""

    @Test fun parsesATypicalRecipeObject() {
        val recipes = SchemaOrgRecipeParser.parse(
            page(
                """
                {"@type":"Recipe","name":"Pancakes","description":"Fluffy ones",
                 "url":"https://example.com/pancakes","author":{"name":"Jane Cook"},
                 "recipeYield":"4 servings","recipeIngredient":["2 cups flour","1 egg"],
                 "recipeInstructions":[{"@type":"HowToStep","text":"Mix"},{"@type":"HowToStep","text":"Fry"}],
                 "prepTime":"PT10M","cookTime":"PT1H30M","image":"https://example.com/p.jpg"}
                """,
            ),
        )
        assertEquals(1, recipes.size)
        val r = recipes[0]
        assertEquals("Pancakes", r.name)
        assertEquals("Fluffy ones", r.introduction)
        assertEquals("Jane Cook", r.source)
        assertEquals("https://example.com/pancakes", r.sourceDetails)
        assertEquals("4 servings", r.yield)
        assertEquals(4, r.servings)
        assertEquals(listOf("2 cups flour", "1 egg"), r.ingredients.map { it.text })
        assertEquals(listOf("Mix", "Fry"), r.directions.map { it.text })
        assertEquals(listOf("Prep" to "10 min", "Cook" to "1 hr 30 min"), r.preparationTimes.map { it.type to it.timeString })
        assertEquals("https://example.com/p.jpg", r.imageUrl)
    }

    @Test fun findsRecipeNestedInAtGraph() {
        val html = page("""{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"Recipe","name":"Soup"}]}""")
        assertEquals(listOf("Soup"), SchemaOrgRecipeParser.parse(html).map { it.name })
    }

    @Test fun findsRecipeInATopLevelArray() {
        val html = page("""[{"@type":"Organization"},{"@type":"Recipe","name":"Stew"}]""")
        assertEquals(listOf("Stew"), SchemaOrgRecipeParser.parse(html).map { it.name })
    }

    @Test fun acceptsArrayValuedAtType() {
        val html = page("""{"@type":["Recipe","NewsArticle"],"name":"Hybrid"}""")
        assertEquals(listOf("Hybrid"), SchemaOrgRecipeParser.parse(html).map { it.name })
    }

    @Test fun ignoresNonRecipeTypes() {
        val html = page("""{"@type":"Article","name":"Not a recipe"}""")
        assertTrue(SchemaOrgRecipeParser.parse(html).isEmpty())
    }

    @Test fun returnsNothingForPagesWithoutJsonLd() {
        assertTrue(SchemaOrgRecipeParser.parse("<html><body>no structured data</body></html>").isEmpty())
        // A script tag of another type must not be scanned as JSON-LD.
        assertTrue(SchemaOrgRecipeParser.parse("""<script type="text/javascript">var x = 1;</script>""").isEmpty())
    }

    @Test fun malformedJsonDoesNotThrow() {
        assertTrue(SchemaOrgRecipeParser.parse(page("{not json at all")).isEmpty())
    }

    @Test fun handlesInstructionsAsPlainStringsOrOneString() {
        val asArray = page("""{"@type":"Recipe","name":"A","recipeInstructions":["Step one","Step two"]}""")
        assertEquals(listOf("Step one", "Step two"), SchemaOrgRecipeParser.parse(asArray)[0].directions.map { it.text })

        val asString = page("""{"@type":"Recipe","name":"A","recipeInstructions":"Just do it"}""")
        assertEquals(listOf("Just do it"), SchemaOrgRecipeParser.parse(asString)[0].directions.map { it.text })
    }

    @Test fun flattensHowToSectionsIntoSteps() {
        val html = page(
            """{"@type":"Recipe","name":"A","recipeInstructions":[
                 {"@type":"HowToSection","name":"Dough","itemListElement":[
                    {"@type":"HowToStep","text":"Knead"},{"@type":"HowToStep","text":"Rest"}]}]}""",
        )
        assertEquals(listOf("Knead", "Rest"), SchemaOrgRecipeParser.parse(html)[0].directions.map { it.text })
    }

    @Test fun readsAuthorAsStringObjectOrArray() {
        fun author(json: String) = SchemaOrgRecipeParser.parse(page("""{"@type":"Recipe","name":"A","author":$json}"""))[0].source
        assertEquals("Jane", author(""""Jane""""))
        assertEquals("Jane", author("""{"name":"Jane"}"""))
        assertEquals("Jane, Bob", author("""[{"name":"Jane"},{"name":"Bob"}]"""))
        assertEquals("", SchemaOrgRecipeParser.parse(page("""{"@type":"Recipe","name":"A"}"""))[0].source)
    }

    @Test fun readsImageAsStringObjectOrArray() {
        fun image(json: String) = SchemaOrgRecipeParser.parse(page("""{"@type":"Recipe","name":"A","image":$json}"""))[0].imageUrl
        assertEquals("u", image(""""u""""))
        assertEquals("u", image("""{"url":"u"}"""))
        assertEquals("first", image("""["first","second"]"""))
        assertEquals("first", image("""[{"url":"first"}]"""))
        assertNull(SchemaOrgRecipeParser.parse(page("""{"@type":"Recipe","name":"A"}"""))[0].imageUrl)
    }

    @Test fun servingsFallsBackToNutritionServingSize() {
        val html = page("""{"@type":"Recipe","name":"A","nutrition":{"servingSize":"Serves 6"}}""")
        assertEquals(6, SchemaOrgRecipeParser.parse(html)[0].servings)
        assertNull(SchemaOrgRecipeParser.parse(page("""{"@type":"Recipe","name":"A"}"""))[0].servings)
    }

    @Test fun decodesHtmlEntitiesWithoutDoubleDecoding() {
        val html = page("""{"@type":"Recipe","name":"Salt &amp; Pepper","description":"&amp;lt;b&amp;gt;"}""")
        val r = SchemaOrgRecipeParser.parse(html)[0]
        assertEquals("Salt & Pepper", r.name)
        // &amp;lt; must become the literal "&lt;", not "<".
        assertEquals("&lt;b&gt;", r.introduction)
    }

    @Test fun formatsIso8601Durations() {
        assertEquals("15 min", SchemaOrgRecipeParser.formatDuration("PT15M"))
        assertEquals("1 hr 30 min", SchemaOrgRecipeParser.formatDuration("PT1H30M"))
        assertEquals("2 hr", SchemaOrgRecipeParser.formatDuration("PT2H"))
        // Non-ISO input passes through untouched.
        assertEquals("about an hour", SchemaOrgRecipeParser.formatDuration("about an hour"))
        // Seconds are dropped, so this renders to nothing at all — and then keeps its own text rather
        // than becoming a preparation time with no time in it.
        assertEquals("PT45S", SchemaOrgRecipeParser.formatDuration("PT45S"))
    }

    /**
     * Recipe plugins write a numeric character reference for every fraction they print, so a WordPress
     * ingredient list is full of them; `&frac12;` and `&nbsp;` are just as common. See [HtmlEntities].
     */
    @Test fun decodesNumericAndNamedCharacterReferences() {
        val html = page(
            """{"@type":"Recipe","name":"A","recipeIngredient":
                 ["&#8531; cup oil","&frac12; cup honey","1&nbsp;egg","&#x2154; cup flour","2 apples &ndash; peeled"]}""",
        )
        assertEquals(
            listOf("\u2153 cup oil", "\u00bd cup honey", "1 egg", "\u2154 cup flour", "2 apples \u2013 peeled"),
            SchemaOrgRecipeParser.parse(html)[0].ingredients.map { it.text },
        )
    }

    /** An ampersand that isn't a reference is just an ampersand, and must not eat what follows it. */
    @Test fun leavesLoneAmpersandsAndUnknownReferencesAlone() {
        val html = page("""{"@type":"Recipe","name":"AT&T &notarealentity; &amp; Sons"}""")
        assertEquals("AT&T &notarealentity; & Sons", SchemaOrgRecipeParser.parse(html)[0].name)
    }

    @Test fun scriptTagAttributeOrderAndQuotingVary() {
        val html = """<script data-x="1" type='application/ld+json' >{"@type":"Recipe","name":"Q"}</script>"""
        assertEquals(listOf("Q"), SchemaOrgRecipeParser.parse(html).map { it.name })
    }
}
