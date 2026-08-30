package com.enuvro.saltykmp.importer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fetching a page and turning it into a recipe. The parsing itself is [SchemaOrgRecipeParserTest]'s
 * job; this covers what the importer decides around it.
 */
class RecipeWebImporterTest {

    private fun page(jsonLd: String) =
        """<html><head><script type="application/ld+json">$jsonLd</script></head><body>x</body></html>"""

    private fun serving(body: String) = MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
    }

    /**
     * AllRecipes, among others, publishes a Recipe with no `url` in it. The address is then the only
     * record of where the recipe came from, which is what the field is for. A bare address gets the
     * https the importer added, since that is what was actually fetched.
     */
    @Test fun fallsBackToTheAddressWhenThePageDeclaresNoUrl() = runTest {
        val importer = RecipeWebImporter(serving(page("""{"@type":"Recipe","name":"Pancakes"}""")))

        val result = importer.import("example.com/pancakes")

        assertIs<WebImportResult.Success>(result)
        assertEquals("https://example.com/pancakes", result.recipe.sourceDetails)
        importer.close()
    }

    @Test fun keepsTheUrlThePageDeclares() = runTest {
        val importer = RecipeWebImporter(
            serving(page("""{"@type":"Recipe","name":"Pancakes","url":"https://example.com/canonical"}""")),
        )

        val result = importer.import("https://example.com/short")

        assertIs<WebImportResult.Success>(result)
        assertEquals("https://example.com/canonical", result.recipe.sourceDetails)
        importer.close()
    }

    @Test fun reportsAPageWithNoRecipeData() = runTest {
        val importer = RecipeWebImporter(serving("<html><body>Just an article</body></html>"))

        assertEquals(WebImportResult.NoRecipeFound, importer.import("https://example.com/blog"))
        importer.close()
    }

    /**
     * The SSRF guard: an importer that takes an arbitrary address would otherwise be a way to make the
     * app read local files. Nothing is requested at all.
     */
    @Test fun refusesAnythingThatIsNotAWebAddress() = runTest {
        val engine = serving(page("""{"@type":"Recipe","name":"Nope"}"""))
        val importer = RecipeWebImporter(engine)

        for (address in listOf("file:///etc/passwd", "ftp://example.com/recipe", "  ")) {
            assertIs<WebImportResult.Failed>(importer.import(address))
        }

        assertTrue(engine.requestHistory.isEmpty())
        importer.close()
    }
}
