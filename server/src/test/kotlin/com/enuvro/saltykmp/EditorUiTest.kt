package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.auth.JwtService
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.image.ImageStore
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Browser tests for the web editor.
 *
 * These exist because every UI defect found while building this screen was plainly visible on
 * screen and completely invisible in the code or in a server-side test: an invalid `appearance`
 * value that silently fell back, buttons collapsed to 16px by a compounding density default, icons
 * rendering at 300px because an <svg> had no intrinsic size, list rows centred by a flex default,
 * a rating whose click target was wider than its stars, and an Alpine binding that removed an
 * attribute instead of writing "false". None of those would fail a Ktor test.
 *
 * Playwright drives a real browser; it downloads its own browsers on first run and needs no Node
 * toolchain in this build. The whole class is skipped rather than failed when a browser can't be
 * launched (a CI image with no download, say), so it never blocks the rest of the suite.
 */
class EditorUiTest {

    private val jwt = JwtService("test-secret", "salty", "salty-app", validityMs = 60_000)
    private val imageStore = ImageStore(Files.createTempDirectory("salty-ui-img"))

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var port = 0

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:saltyui;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }

        const val RECIPE_ID = "01A05100-0000-7000-8000-0000000000R1"
        const val COURSE_ID = "01A05100-0000-7000-8000-0000000000C1"
        const val CATEGORY_ID = "01A05100-0000-7000-8000-0000000000K1"
    }

    /** Null when no browser can be launched, which turns every test into a skip. */
    private fun browserOrNull(): Browser? {
        if (browser != null) return browser
        return runCatching {
            playwright = Playwright.create()
            playwright!!.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }.getOrNull()?.also { browser = it }
    }

    @BeforeTest
    fun setUp() {
        ensureDb()
        runBlocking {
            DatabaseFactory.dbQuery {
                RecipeTags.deleteAll(); RecipeCategories.deleteAll()
                Recipes.deleteAll(); Courses.deleteAll(); Categories.deleteAll(); Tags.deleteAll()
                ShoppingLists.deleteAll(); DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            val user = UserRepository.create("tester", "pw")
            LibraryRepository.upsertCourse(user.id, ServerCourse(COURSE_ID, "Breads"))
            LibraryRepository.upsertCategory(user.id, ServerCategory(CATEGORY_ID, "Baking"))
            RecipeRepository.upsert(
                user.id,
                ServerRecipe(
                    id = RECIPE_ID,
                    name = "Skillet Cornbread",
                    lastModifiedDate = "2026-08-01T00:00:00.000Z",
                    yield = "8 servings",
                    servings = 8,
                    rating = 4,
                    courseId = COURSE_ID,
                    categoryIds = listOf(CATEGORY_ID),
                    ingredients = listOf(
                        Ingredient(id = "i1", text = "Dry", isHeading = true),
                        Ingredient(id = "i2", text = "1 1/2 cups yellow cornmeal", isMain = true),
                        Ingredient(id = "i3", text = "1/2 cup all-purpose flour"),
                    ),
                    directions = listOf(
                        Direction(id = "d1", text = "Heat the skillet."),
                        Direction(id = "d2", text = "Bake", isHeading = true),
                        Direction(id = "d3", text = "Bake 22 minutes."),
                    ),
                ),
            )
        }
        server = embeddedServer(Netty, port = 0) { installSalty(jwt, imageStore) }.also { it.start(wait = false) }
        port = runBlocking { server!!.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
        browser?.close()
        playwright?.close()
        browser = null
        playwright = null
    }

    /** Signs in through the real login form and lands on the editor. */
    private fun editorPage(b: Browser): Page {
        val page = b.newPage(Browser.NewPageOptions().setViewportSize(1400, 900))
        page.navigate("http://localhost:$port/login")
        page.getByLabel("Username").fill("tester")
        page.getByLabel("Password").fill("pw")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Sign in")
        ).first().click()
        page.navigate("http://localhost:$port/editor")
        // The editor is client-rendered; wait for the list to arrive rather than a fixed sleep.
        page.waitForSelector(".rrow")
        // The autoloader defines <wa-*> elements asynchronously as it discovers them, so give it
        // a moment to settle rather than racing it.
        page.waitForFunction(
            """() => [...new Set([...document.querySelectorAll('*')]
                   .map(e => e.tagName.toLowerCase()).filter(t => t.startsWith('wa-')))]
                   .every(t => customElements.get(t))"""
        )
        return page
    }

    @Test
    fun theEditorRendersAndComponentsUpgrade() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)

        // If Web Awesome fails to load (the dist vs dist-cdn trap), custom elements never upgrade
        // and everything collapses to unstyled inline text. Assert on a real upgraded component.
        val upgraded = page.evaluate(
            """() => {
                 const tags = [...document.querySelectorAll('*')]
                   .map(e => e.tagName.toLowerCase()).filter(t => t.startsWith('wa-'));
                 const unique = [...new Set(tags)];
                 return { total: tags.length, undefinedOnes: unique.filter(t => !customElements.get(t)) };
               }"""
        ) as Map<*, *>
        assertTrue((upgraded["total"] as Int) > 5, "expected Web Awesome components on the page")
        assertEquals(
            emptyList<String>(), upgraded["undefinedOnes"],
            "every <wa-*> element should have upgraded; leftovers mean the loader failed",
        )
        page.close()
    }

    @Test
    fun readIsTheDefaultAndEditIsAnAction() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")

        assertThat(page.locator(".read")).isVisible()
        assertThat(page.locator(".edit")).hasCount(0)
        // The read view is for reading: no row-adding controls in it.
        assertThat(page.getByText("Section heading")).hasCount(0)

        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")
        assertThat(page.locator(".edit")).isVisible()
        page.close()
    }

    /** Section headings are list items too; an <ol> counted them and skipped a number. */
    @Test
    fun directionStepsSkipHeadingsWhenNumbering() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read__steps")

        val numbers = page.locator(".read__steps .stepno").allTextContents()
        assertEquals(listOf("1.", "2."), numbers, "a heading between steps must not consume a number")
        page.close()
    }

    /**
     * The reported bug: wa-rating is role="slider" and maps click position across its ELEMENT
     * width, so a box stretched by its flex parent put the fifth star's hit region past the
     * visible stars. Clicking the far right must therefore yield 5.
     */
    @Test
    fun everyRatingStarIsClickableIncludingTheLast() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        // Not waitForSelector(".rating"): a <wa-rating> that hasn't upgraded yet has a zero-sized
        // box, which Playwright treats as not visible. Wait for the definition and a real box.
        page.waitForFunction(
            """() => customElements.get('wa-rating')
                    && document.querySelector('.rating-field')?.getBoundingClientRect().width > 0"""
        )

        val rating = page.locator(".rating-field")
        // page.mouse() works in VIEWPORT coordinates and does no auto-scrolling of its own, unlike
        // locator.click(). The rating sits in the Details card below the fold, so without this the
        // click lands on whatever happens to occupy those coordinates instead.
        rating.scrollIntoViewIfNeeded()
        val box = rating.boundingBox()
        assertTrue(box.width < 220, "the rating must hug its stars, not stretch (was ${box.width}px)")

        // Click the centre of the last fifth — the star that was unreachable.
        page.mouse().click(box.x + box.width * 0.9, box.y + box.height / 2)
        // Number(): wa-rating exposes value as a string, which is why the app's own change handler
        // coerces it too. A strict === against 5 would never match.
        page.waitForFunction("() => Number(document.querySelector('.rating-field').value) === 5")
        assertEquals(5, (rating.evaluate("el => Number(el.value)") as Number).toInt())
        page.close()
    }

    /** The hamburger does one thing: show and hide the sidebar. */
    @Test
    fun hamburgerTogglesTheSidebar() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        val rail = page.locator(".rail")
        assertThat(rail).isVisible()

        page.locator("[data-testid=hamburger]").click()
        assertThat(rail).not().isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(3000.0))

        page.locator("[data-testid=hamburger]").click()
        assertThat(rail).isVisible()
        page.close()
    }

    /**
     * Selecting a library entry filters the middle pane without a page load.
     *
     * Clicks are real, not dispatched: wa-tree drives selection from trusted pointer input, and
     * synthetic events do not move it. That is exactly why this test earns its keep.
     */
    @Test
    fun librarySelectionFiltersTheRecipeList() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        assertThat(page.locator(".rrow")).hasCount(1)

        page.locator("wa-tree-item[data-kind=category]").first().click()
        assertThat(page.locator(".list__title")).hasText("Baking")
        assertThat(page.locator(".rrow")).hasCount(1)

        // Favourites: nothing is flagged in the fixture, so the list should empty out.
        page.locator("wa-tree-item[data-kind=favorites]").click()
        assertThat(page.locator(".list__title")).hasText("Favorites")
        assertThat(page.locator(".rrow")).hasCount(0)

        // Back to everything.
        page.locator("wa-tree-item[data-kind=all]").click()
        assertThat(page.locator(".rrow")).hasCount(1)
        page.close()
    }

    /** Group rows (Categories, Courses, ...) are containers: selecting one must not filter. */
    @Test
    fun expandingAGroupDoesNotChangeTheFilter() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        val title = page.locator(".list__title")
        assertThat(title).hasText("All Recipes")

        // Click the group's own row, not its centre: an expanded wa-tree-item's box encloses its
        // children, so a centre-click lands on whichever child sits in the middle.
        page.locator("wa-tree-item[data-group]").first()
            .click(com.microsoft.playwright.Locator.ClickOptions().setPosition(30.0, 10.0))
        assertThat(title).hasText("All Recipes")
        page.close()
    }

    /** An edit made in the browser must reach the API and come back on reload. */
    @Test
    fun editingARecipePersistsThroughTheApi() {
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".titlefield")

        page.locator(".titlefield").evaluate(
            """el => { el.value = 'Renamed In Browser';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        page.locator("[data-testid=save]").click()
        // The save bar returns to "All changes saved" once the PUT resolves — a more direct signal
        // than the toast, whose text is slotted inside a component.
        page.waitForFunction(
            "() => !Alpine.\$data(document.getElementById('app')).dirty"
        )

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals("Renamed In Browser", stored?.name, "the browser edit should have reached the API")
        page.close()
    }
}
