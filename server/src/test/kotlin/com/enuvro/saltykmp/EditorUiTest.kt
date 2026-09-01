package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.recipe.addressRefusal
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingListRepository
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.ShoppingListListContents
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
 * toolchain in this build. When a browser cannot be launched (a CI image with no download, say)
 * these are SKIPPED, not passed: the earlier `?: return` reported 33 green tests on a machine that
 * had never opened a browser, which is indistinguishable from a real run and hides the loss of the
 * only coverage ~2000 lines of app.js and app.mustache have.
 */
class EditorUiTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-ui-img"))

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var port = 0
    private var recipeSite: HttpServer? = null
    private var sitePort = 0

    companion object {
        /** What the stand-in site serves: the structured data a real recipe page publishes. */
        private val IMPORTABLE_PAGE = """
            <!doctype html><html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe",
             "name":"Imported Cornbread",
             "recipeYield":"8 wedges",
             "recipeIngredient":["1 cup cornmeal","1 cup buttermilk"],
             "recipeInstructions":[{"@type":"HowToStep","text":"Heat the skillet."}]}
            </script></head><body>Cornbread</body></html>
        """.trimIndent()

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
        const val LONG_RECIPE_NAME = "Veggie Burger (Grind Burger Kitchen) with Caramelised Onion Relish"
        const val LONG_CATEGORY_NAME = "Slow-Cooked Braises, Stews and Other Long Sunday Projects"
        const val TAG_ID = "01A05100-0000-7000-8000-0000000000T1"
        const val LIST_ID = "01A05100-0000-7000-8000-0000000000L1"
    }

    /** Skips the test (does not pass it) when no browser can be launched. */
    private fun requireBrowser(): Browser {
        val b = browserOrNull()
        org.junit.Assume.assumeTrue("no Playwright browser available; skipping browser tests", b != null)
        return b!!
    }

    /** Null when no browser can be launched. */
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
        // A stand-in recipe site for the web import. It runs on loopback, so the app's server is
        // given a policy that permits loopback specifically -- everything else still goes through
        // the real one. See AddressPolicy in RecipeImport.kt for why that seam exists.
        recipeSite = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/recipe") { exchange ->
                val bytes = IMPORTABLE_PAGE.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        sitePort = recipeSite!!.address.port

        server = embeddedServer(Netty, port = 0) {
            installSalty(imageStore, importAddressPolicy = { host ->
                if (host == "127.0.0.1" || host == "localhost") null else addressRefusal(host)
            })
        }.also { it.start(wait = false) }
        port = runBlocking { server!!.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun tearDown() {
        recipeSite?.stop(0)
        recipeSite = null
        server?.stop(0, 0)
        browser?.close()
        playwright?.close()
        browser = null
        playwright = null
    }

    /** Signs in through the real login form and lands on the editor. */
    private fun editorPage(b: Browser, initScript: String? = null): Page {
        val page = b.newPage(Browser.NewPageOptions().setViewportSize(1400, 900))
        // Before any navigation, so a stubbed platform API is in place by the time app.js runs.
        if (initScript != null) page.addInitScript(initScript)
        page.navigate("http://localhost:$port/login")
        page.getByLabel("Username").fill("tester")
        page.getByLabel("Password").fill("pw")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Sign in")
        ).first().click()
        // Wait for the login POST's own 302 to land on /app rather than navigating there ourselves.
        //
        // This used to be a bare `page.navigate(".../app")` immediately after the click, which is a
        // race the click loses often enough to matter: the request could go out before the login
        // response had set SALTY_SESSION, arrive unauthenticated, get bounced back to /login, and
        // then spend 30s waiting for a `.rrow` that was never going to render. It surfaced as a
        // different test timing out on almost every run -- which is worse than a test that always
        // fails, because it reads as noise and trains you to re-run instead of look.
        page.waitForURL("**/app")
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
        val b = requireBrowser()
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
        val b = requireBrowser()
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
        val b = requireBrowser()
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
        val b = requireBrowser()
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

    /**
     * There must be exactly ONE navigation toggle. A hand-rolled hamburger in the header used to
     * sit next to the one wa-page renders itself, and every attempt to suppress one of them broke
     * the other -- ending with a narrow screen where neither opened the sidebar.
     */
    @Test
    fun thereIsExactlyOneNavigationToggle() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.waitForSelector("wa-page")

        val toggles = page.evaluate(
            """() => {
                 const p = document.getElementById('app');
                 const inShadow = p.shadowRoot.querySelectorAll('[part~="navigation-toggle"]').length;
                 const inLight = document.querySelectorAll('wa-icon[name="bars"]').length;
                 return inShadow + inLight;
               }"""
        )
        assertEquals(1, toggles, "exactly one hamburger, drawn by wa-page itself")
        page.close()
    }

    /**
     * On a narrow screen the sidebar is wa-page's drawer: hidden until asked for, then shown.
     * It used to be unreachable there -- the header button toggled a rail that mobile CSS hides,
     * and the "Library" button set a pane value no CSS rule matched.
     */
    @Test
    fun theSidebarOpensOnANarrowScreen() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.setViewportSize(420, 800)
        // wa-page switches view from a ResizeObserver, so wait for it rather than assuming.
        page.waitForFunction("() => document.getElementById('app').getAttribute('view') === 'mobile'")

        val rail = page.locator("nav.rail")
        assertThat(rail).not().isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(3000.0))

        page.evaluate("() => document.getElementById('app').shadowRoot.querySelector('[part~=\"navigation-toggle\"]').click()")
        assertThat(rail).isVisible()
        page.close()
    }

    /**
     * The whole compact chain: recipe -> back to the list -> back to the library. On a narrow
     * screen the app opens showing a recipe, and each step has to be reachable. The last step used
     * to set a pane value no CSS rule matched, so the library was a dead end.
     */
    @Test
    fun theCompactBackChainReachesTheLibrary() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")

        page.setViewportSize(420, 800)
        page.waitForFunction("() => document.getElementById('app').getAttribute('view') === 'mobile'")

        val rail = page.locator("nav.rail")
        val list = page.locator("section[aria-label='Recipes']")
        assertThat(rail).not().isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(3000.0))

        // Detail -> list.
        page.locator("section.detail .only-compact").first().click()
        assertThat(list).isVisible()

        // List -> library drawer.
        page.locator("section[aria-label='Recipes'] .only-compact").first().click()
        assertThat(rail).isVisible()
        page.close()
    }

    /**
     * Web Awesome styles a bare <main> as a document, with 48px of padding. This is an app shell;
     * on a 420px phone that padding was eating nearly a quarter of the width.
     */
    @Test
    fun thePanesMeetTheWindowEdges() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.setViewportSize(420, 800)
        val padding = page.evaluate(
            "() => getComputedStyle(document.querySelector('.panes')).paddingLeft")
        assertEquals("0px", padding, "the pane grid should not inherit document padding")
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
        val b = requireBrowser()
        // A second recipe outside every fixture classifier. Without it the counts before and after
        // a category click were both 1, so a filter that stopped filtering entirely still passed.
        seedSecondRecipe()
        // Seeded here rather than in setUp: anEmptyLibraryGroupIsNotSelectable depends on the
        // fixture having no tags at all.
        seedTagOnFixtureRecipe()
        val page = editorPage(b)
        assertThat(page.locator(".rrow")).hasCount(2)

        page.locator("wa-tree-item[data-kind=category]").first().click()
        assertThat(page.locator("section[aria-label='Recipes'] .list__title")).hasText("Baking")
        assertThat(page.locator(".rrow")).hasCount(1)

        // Courses and tags run through the same predicate but had no coverage at all. Their groups
        // start collapsed, so open them the way a user would before the rows can be clicked.
        page.evaluate(
            "() => document.querySelectorAll('wa-tree-item[data-group]').forEach(g => g.expanded = true)")
        page.locator("wa-tree-item[data-kind=course]").first().click()
        assertThat(page.locator(".rrow")).hasCount(1)
        page.locator("wa-tree-item[data-kind=tag]").first().click()
        assertThat(page.locator(".rrow")).hasCount(1)

        // Favourites: nothing is flagged in the fixture, so the list should empty out.
        page.locator("wa-tree-item[data-kind=favorites]").click()
        assertThat(page.locator("section[aria-label='Recipes'] .list__title")).hasText("Favorites")
        assertThat(page.locator(".rrow")).hasCount(0)

        // Back to everything.
        page.locator("wa-tree-item[data-kind=all]").click()
        assertThat(page.locator(".rrow")).hasCount(2)
        page.close()
    }

    /** Gives the fixture recipe a tag, so the tag branch of the filter has something to match. */
    private fun seedTagOnFixtureRecipe() = runBlocking {
        val userId = UserRepository.findByUsername("tester")!!.id
        LibraryRepository.upsertTag(userId, com.enuvro.saltykmp.api.ServerTag(TAG_ID, "Quick"))
        val recipe = RecipeRepository.getById(userId, RECIPE_ID)!!
        RecipeRepository.upsert(userId, recipe.copy(tagIds = listOf(TAG_ID)))
    }

    /** A recipe in no category, no course and no tag, so filters have something to exclude. */
    private fun seedSecondRecipe() = runBlocking {
        RecipeRepository.upsert(
            UserRepository.findByUsername("tester")!!.id,
            ServerRecipe(
                id = "01A05100-0000-7000-8000-0000000000R2",
                name = "Unfiled Soup",
                lastModifiedDate = "2026-08-01T00:00:00.000Z",
            ),
        )
    }

    /**
     * The recipe column is ONE tab stop, not one per recipe.
     *
     * Bare sibling buttons put every row in the tab order, so reaching the recipe pane past a
     * 200-recipe list meant 200 presses. Exactly one row is tabbable and the arrows move between
     * them -- and they move FOCUS, not the selection: selection-follows-focus would read a recipe
     * off the server on every keypress, so Enter is what opens one.
     */
    @Test
    fun theRecipeListIsOneTabStopAndArrowsMoveWithinIt() {
        val b = requireBrowser()
        seedSecondRecipe()
        val page = editorPage(b)
        val rows = page.locator("section[aria-label='Recipes'] .rrow")
        val title = page.locator("section[aria-label='Recipe'] .detail__title")
        assertThat(rows).hasCount(2)

        // A real list, explicitly roled: `list-style: none` drops list semantics in Safari, and
        // those semantics are what announce "1 of 2" instead of an unbounded run of "button".
        assertThat(page.locator("section[aria-label='Recipes'] ul.list__rows"))
            .hasAttribute("role", "list")
        assertThat(page.locator("section[aria-label='Recipes'] .list__rows > li")).hasCount(2)

        // With nothing selected the tab stop falls back to the first row, which is what keeps the
        // column reachable at all on the landing state.
        assertThat(rows.nth(0)).hasAttribute("tabindex", "0")
        assertThat(rows.nth(1)).hasAttribute("tabindex", "-1")

        // From here the subject is an OPEN recipe staying put while focus moves, so open one.
        openFirstRecipe(page)
        assertThat(rows.nth(0)).hasAttribute("tabindex", "0")

        rows.nth(0).focus()
        page.keyboard().press("ArrowDown")
        assertEquals("Unfiled Soup", focusedRowName(page))
        // The tab stop travels with focus, or tabbing away and back lands somewhere else.
        assertThat(rows.nth(1)).hasAttribute("tabindex", "0")
        assertThat(rows.nth(0)).hasAttribute("tabindex", "-1")
        // Focus moved. The open recipe did not.
        assertThat(title).hasText("Skillet Cornbread")

        // Neither end wraps: in a scrolling list, jumping from the last row to the first loses
        // your place more than it saves a keystroke.
        page.keyboard().press("ArrowDown")
        assertEquals("Unfiled Soup", focusedRowName(page))
        page.keyboard().press("Home")
        assertEquals("Skillet Cornbread", focusedRowName(page))
        page.keyboard().press("ArrowUp")
        assertEquals("Skillet Cornbread", focusedRowName(page))
        page.keyboard().press("End")
        assertEquals("Unfiled Soup", focusedRowName(page))

        // Enter opens it, which is the row being a real <button> and nothing more.
        page.keyboard().press("Enter")
        assertThat(title).hasText("Unfiled Soup")
        page.close()
    }

    /**
     * The frame is pinned to the viewport and the COLUMNS scroll inside it -- the window does not.
     *
     * wa-page is a document layout: it scrolls as a whole, and its main region is an auto-sized
     * grid row, so with twenty recipes the whole page grew to 1526px and scrolled. .rail,
     * .list__scroll and .doc were all carrying `overflow-y: auto` with nothing to overflow, and
     * scrolling the recipe list dragged the search box and the open recipe off the screen with it.
     * Nothing about that is visible in the markup, and every three-pane assumption in this
     * stylesheet depends on it, so it is asserted here.
     */
    @Test
    fun theColumnsScrollInsteadOfTheWindow() {
        val b = requireBrowser()
        seedManyRecipes()
        val page = editorPage(b)
        page.waitForFunction("() => document.querySelectorAll('.rrow').length > 15")

        val m = page.evaluate(
            """() => {
                 const col = document.querySelector('.list__scroll');
                 return { page: document.documentElement.scrollHeight, win: window.innerHeight,
                          colScroll: col.scrollHeight, colClient: col.clientHeight };
               }"""
        ) as Map<*, *>

        assertEquals(m["win"], m["page"], "the window itself must not scroll")
        assertTrue(
            (m["colScroll"] as Int) > (m["colClient"] as Int),
            "the recipe column should be the thing that scrolls, and should overflow with 20 rows",
        )
        page.close()
    }

    /**
     * The list/recipe divider: drag it, arrow it, and it is still there after a reload.
     *
     * wa-split-panel's start/end slots are `display: contents`, so the four <section>s are the grid
     * items themselves and the layout only holds while exactly one of each pair is x-shown. That is
     * invisible in the markup and would fail as a silent second column, so it is asserted here.
     */
    @Test
    fun theListDividerResizesAndIsRemembered() {
        val b = requireBrowser()
        val page = editorPage(b)
        val list = page.locator("section[aria-label='Recipes']")
        val divider = page.locator("wa-split-panel [part~='divider']")

        assertThat(divider).isVisible()
        val start = list.boundingBox().width
        assertEquals(280.0, start, 2.0, "the divider should start where the fixed column used to be")

        // Drag it right. The divider is a 1px seam with an 11px hit area, so this also covers the
        // hit area actually being grabbable.
        val d = divider.boundingBox()
        page.mouse().move(d.x + d.width / 2, d.y + d.height / 2)
        page.mouse().down()
        page.mouse().move(d.x + d.width / 2 + 120, d.y + d.height / 2)
        page.mouse().up()
        val dragged = list.boundingBox().width
        assertTrue(dragged > start + 100, "dragging right should widen the list, got $dragged")

        // Exactly one column of recipes and one of recipe: a section that failed to hide would
        // show up as a fourth grid item and steal width from these two.
        assertThat(page.locator("section[aria-label='Shopping lists']")).isHidden()
        assertThat(page.locator("section[aria-label='Shopping list']")).isHidden()

        // The divider is role="separator" with tabindex="0" straight from the component, so it
        // resizes from the keyboard with nothing of ours involved.
        divider.focus()
        page.keyboard().press("ArrowLeft")
        page.keyboard().press("ArrowLeft")
        val arrowed = list.boundingBox().width
        assertTrue(arrowed < dragged, "the arrow keys should narrow the list, got $arrowed")

        // Remembered across a reload, which is the point of persisting it at all. The wait is the
        // listener's own debounce: wa-reposition fires per pointer move, so the write is deferred
        // to the resting width.
        page.waitForTimeout(400.0)
        page.reload()
        page.waitForSelector(".rrow")
        page.waitForFunction(
            "w => Math.abs(document.querySelector('.list').getBoundingClientRect().width - w) < 3",
            arrowed,
        )
        page.close()
    }

    /**
     * A long recipe name is clipped, not chased.
     *
     * WA's native.css puts `white-space: nowrap` on every <button> and a row is one, so a long name
     * did not wrap -- it overflowed, and the whole COLUMN scrolled sideways to follow it. The
     * subtitle had been ellipsed since it was written; the name never was.
     */
    @Test
    fun aLongRecipeNameIsClippedRatherThanScrolled() {
        val b = requireBrowser()
        seedLongNamedRecipe()
        val page = editorPage(b)
        val name = page.locator(".rrow__name", Page.LocatorOptions().setHasText("Veggie Burger"))

        val m = page.evaluate(
            """() => {
                 const scroll = document.querySelector('.list__scroll');
                 const el = [...document.querySelectorAll('.rrow__name')]
                   .find(n => n.textContent.includes('Veggie'));
                 const row = el.closest('.rrow');
                 const box = e => e.getBoundingClientRect();
                 return { sideways: scroll.scrollWidth > scroll.clientWidth,
                          clipped: el.scrollWidth > el.clientWidth,
                          // the row's insets, which the <li> wrapper's inherited bullet margin
                          // had knocked 18px out of true on the left only
                          leftGap: Math.round(box(row).left - box(scroll).left),
                          rightGap: Math.round(box(scroll).right - box(row).right),
                          full: el.textContent };
               }"""
        ) as Map<*, *>

        assertEquals(false, m["sideways"], "the column must not scroll sideways to chase a name")
        assertEquals(true, m["clipped"], "the name should be clipped")
        assertEquals(m["rightGap"], m["leftGap"], "the row should sit evenly between the edges")
        // Clipping is visual only. The full name is still in the DOM, so a screen reader was never
        // missing anything -- which is why this is not an aria-label.
        assertEquals(LONG_RECIPE_NAME, m["full"])

        // The tooltip is set on hover, and only when there is something to reveal.
        name.hover()
        assertEquals(LONG_RECIPE_NAME, name.getAttribute("title"))
        val short = page.locator(".rrow__name", Page.LocatorOptions().setHasText("Skillet"))
        short.hover()
        assertEquals(null, short.getAttribute("title"), "an unclipped name needs no tooltip")
        page.close()
    }

    /**
     * One long classifier name must not eat the window.
     *
     * wa-page's --menu-width defaults to `auto`, so the rail was as wide as its widest label: a
     * single category called "Slow-Cooked Braises…" took it to 557px of a 1400px window.
     */
    @Test
    fun theRailStopsGrowingForALongClassifierName() {
        val b = requireBrowser()
        runBlocking {
            LibraryRepository.upsertCategory(
                UserRepository.findByUsername("tester")!!.id,
                ServerCategory("01A05100-0000-7000-8000-0000000000LC", LONG_CATEGORY_NAME),
            )
        }
        val page = editorPage(b)
        page.waitForSelector("wa-tree-item[data-kind=category]")

        val m = page.evaluate(
            """() => {
                 const rail = document.querySelector('nav.rail');
                 const label = [...document.querySelectorAll('wa-tree-item span')]
                   .find(s => s.textContent.trim().startsWith('Slow-Cooked'));
                 return { railWidth: Math.round(rail.getBoundingClientRect().width),
                          railSideways: rail.scrollWidth > rail.clientWidth,
                          labelClipped: label.scrollWidth > label.clientWidth,
                          // one line, not three: the cap must ellipse, not wrap
                          labelHeight: Math.round(label.getBoundingClientRect().height) };
               }"""
        ) as Map<*, *>

        assertEquals(240, m["railWidth"], "--menu-width should cap the rail at 15rem")
        assertEquals(false, m["railSideways"], "the rail must not scroll sideways instead")
        assertEquals(true, m["labelClipped"], "the label should ellipse at the cap")
        assertTrue((m["labelHeight"] as Int) < 40, "the label should stay on one line")
        page.close()
    }

    private fun seedLongNamedRecipe() = runBlocking {
        RecipeRepository.upsert(
            UserRepository.findByUsername("tester")!!.id,
            ServerRecipe(
                id = "01A05100-0000-7000-8000-0000000000LN",
                name = LONG_RECIPE_NAME,
                introduction = "As seen on Diners, Drive-Ins and Dives, season 12, episode 4",
                lastModifiedDate = "2026-08-01T00:00:00.000Z",
            ),
        )
    }

    /**
     * Shopping lists are a section of the rail, not a leaf in it: a "Shopping Lists" heading with
     * "All Lists" under it, the shape Categories and Courses already have.
     *
     * The heading is a `data-group`, and the tree is in leaf-selection mode, so it expands and
     * collapses and never navigates. That is the part worth pinning: it would be easy to make the
     * heading the destination again and not notice, because the child sits right under it.
     */
    @Test
    fun shoppingListsAreAHeadingWithAllListsUnderIt() {
        val b = requireBrowser()
        val page = editorPage(b)
        val heading = page.locator("wa-tree-item[data-group]")
            .filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Shopping Lists"))
        val allLists = page.locator("wa-tree-item[data-kind=shopping]")

        assertThat(heading).hasCount(1)
        assertThat(allLists).hasText(java.util.regex.Pattern.compile("All Lists"))
        // The child really is inside the heading, not a sibling that happens to follow it.
        assertThat(heading.locator("wa-tree-item[data-kind=shopping]")).hasCount(1)

        // Clicking the heading collapses it; it does not open the shopping lists.
        heading.click()
        assertThat(page.locator("section[aria-label='Recipes']")).isVisible()
        assertThat(page.locator("section[aria-label='Shopping lists']")).isHidden()

        // The child is the destination.
        heading.click()
        allLists.click()
        assertThat(page.locator("section[aria-label='Shopping lists']")).isVisible()
        assertThat(page.locator("section[aria-label='Recipes']")).isHidden()
        page.close()
    }

    /**
     * In a shopping-list row the NAME is the loud one.
     *
     * The item count had no styling of its own, so it inherited the row's font size and came out
     * larger than the name of the list it belonged to -- the loudest thing in the column, for the
     * least interesting fact in it. It is the recipe rows' subtitle now: second line, smaller,
     * quiet, ellipsed.
     */
    @Test
    fun theShoppingListCountIsQuieterThanItsName() {
        val b = requireBrowser()
        runBlocking {
            ShoppingListRepository.save(
                UserRepository.findByUsername("tester")!!.id,
                ServerShoppingList(
                    id = LIST_ID,
                    name = "Saturday Big Shop for the Whole Extended Family Reunion",
                    isFreeform = false,
                    contentsForList = listOf(ShoppingListListContents(id = "s0", text = "Milk")),
                    lastModifiedDate = "2026-08-01T00:00:00.000Z",
                ),
            )
        }
        val page = editorPage(b)
        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.waitForSelector("section[aria-label='Shopping lists'] .rrow")

        val m = page.evaluate(
            """() => {
                 const row = document.querySelector("section[aria-label='Shopping lists'] .rrow");
                 const name = row.querySelector('.rrow__name');
                 const meta = row.querySelector('.rrow__meta');
                 const px = el => parseFloat(getComputedStyle(el).fontSize);
                 return { nameSize: px(name), metaSize: px(meta),
                          nameColor: getComputedStyle(name).color,
                          metaColor: getComputedStyle(meta).color,
                          // the count sits below the name, not beside it
                          stacked: Math.round(meta.getBoundingClientRect().top) >=
                                   Math.round(name.getBoundingClientRect().bottom),
                          nameClipped: name.scrollWidth > name.clientWidth };
               }"""
        ) as Map<*, *>

        // Number, not Double: a whole-pixel font size comes back from Playwright as an Integer.
        val metaSize = (m["metaSize"] as Number).toDouble()
        val nameSize = (m["nameSize"] as Number).toDouble()
        assertTrue(metaSize < nameSize, "the count should be smaller than the name, got $metaSize vs $nameSize")
        assertTrue(m["nameColor"] != m["metaColor"], "the count should be the quieter colour")
        assertEquals(true, m["stacked"], "the count belongs on its own line under the name")
        assertEquals(true, m["nameClipped"], "a long list name should still ellipse")
        page.close()
    }

    /** Below the pane breakpoint there is one pane and no divider to drag. */
    @Test
    fun theDividerIsGoneOnACompactScreen() {
        val b = requireBrowser()
        val page = editorPage(b)
        // The compact view only has two panes to choose between once one of them holds a recipe.
        openFirstRecipe(page)
        page.setViewportSize(420, 800)
        // wa-page switches view from a ResizeObserver, and until it does the navigation rail is
        // still holding a 186px column -- measure before that and the pane is 234px wide.
        page.waitForFunction("() => document.getElementById('app').getAttribute('view') === 'mobile'")

        assertThat(page.locator("wa-split-panel [part~='divider']")).isHidden()
        // A recipe was opened on the wide screen, so the compact view is showing it and the list is
        // the pane that stepped aside -- which is the existing data-pane behaviour, not the split
        // panel's. What matters here is that the survivor gets the whole window.
        assertThat(page.locator("section[aria-label='Recipes']")).isHidden()
        val open = page.locator("section[aria-label='Recipe']").boundingBox()
        assertEquals(420.0, open.width, 2.0, "the one visible pane should fill the window")
        page.close()
    }

    /**
     * Chef mode: the recipe alone, bigger, with the app out of the way.
     *
     * The width assertion is the one that would silently regress. wa-page's body grid sizes its
     * menu track as `minmax(0, var(--menu-width))`, and hiding the rail alone is not enough --
     * an empty track with a fixed maximum still takes free space up to it, so the recipe kept a
     * 15rem gutter down its left with nothing in it. Nothing about the markup says so.
     */
    @Test
    fun chefModeHidesTheChromeAndEnlargesTheRecipe() {
        val b = requireBrowser()
        val page = editorPage(b)
        val app = page.locator("#app")
        val recipe = page.locator("section[aria-label='Recipe']")

        // Chef mode is a way of reading a recipe, so there has to be one.
        openFirstRecipe(page)
        val normalStep = ingredientFontSize(page)
        page.locator("[data-testid=chef]").click()
        page.waitForFunction("() => document.getElementById('app').hasAttribute('data-chef')")

        assertThat(page.locator(".appbar")).isHidden()
        assertThat(page.locator("nav.rail")).isHidden()
        assertThat(page.locator("section[aria-label='Recipes']")).isHidden()
        assertThat(recipe.locator(".detail__bar")).isHidden()
        // Still a recipe, not a stripped-down one: what you are standing there to read stays.
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Ingredients"))).isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Directions"))).isVisible()

        val box = recipe.boundingBox()
        assertEquals(0.0, box.x, 2.0, "the recipe should start at the window edge, with no empty menu track")
        assertEquals(1400.0, box.width, 2.0, "the recipe should have the whole window")

        val chefStep = ingredientFontSize(page)
        assertTrue(
            chefStep > normalStep * 1.3,
            "ingredients should be substantially larger in chef mode, got $chefStep vs $normalStep",
        )

        // Out again by the one control chef mode leaves on screen, and everything comes back.
        page.locator(".chefexit").click()
        page.waitForFunction("() => !document.getElementById('app').hasAttribute('data-chef')")
        assertThat(page.locator(".appbar")).isVisible()
        assertThat(page.locator("section[aria-label='Recipes']")).isVisible()
        assertEquals(normalStep, ingredientFontSize(page), 0.01, "the reading view should be unchanged after leaving")

        // ...and Escape is the other way out, because that is what a chromeless view has to answer.
        page.locator("[data-testid=chef]").click()
        page.waitForFunction("() => document.getElementById('app').hasAttribute('data-chef')")
        page.keyboard().press("Escape")
        page.waitForFunction("() => !document.getElementById('app').hasAttribute('data-chef')")
        assertEquals(null, app.getAttribute("data-chef"))
        page.close()
    }

    /** The computed size of a plain ingredient row — the text chef mode exists to enlarge. */
    private fun ingredientFontSize(page: Page): Double =
        page.evaluate(
            "() => getComputedStyle(document.querySelector('.read__list li:not(.is-heading)')).fontSize"
        ).toString().removeSuffix("px").toDouble()

    /**
     * A stand-in Screen Wake Lock that records what was asked of it.
     *
     * The real API is refused in headless Chromium, and missing entirely wherever Salty is served
     * over plain http — the normal way to run it on a LAN — so testing against it would be testing
     * the browser's mood. What matters here is Salty's half of the contract: a lock is taken when
     * chef mode opens, given back when it closes, and never asked for once the preference is off.
     */
    private val wakeLockStub = """
        (() => {
          window.__wakeLog = [];
          const makeLock = () => {
            const listeners = [];
            return {
              addEventListener: (type, fn) => { if (type === 'release') listeners.push(fn); },
              release() {
                window.__wakeLog.push('release');
                listeners.forEach(fn => fn());
                return Promise.resolve();
              },
            };
          };
          Object.defineProperty(navigator, 'wakeLock', {
            configurable: true,
            value: {
              request: () => { window.__wakeLog.push('request'); return Promise.resolve(makeLock()); },
            },
          });
        })();
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun wakeLog(page: Page): List<String> =
        (page.evaluate("() => window.__wakeLog") as List<Any?>).map { it.toString() }

    /** Chef mode keeps the screen on, and Preferences is where that is turned off. */
    @Test
    fun chefModeHoldsAScreenWakeLockUntilThePreferenceSaysOtherwise() {
        val b = requireBrowser()
        val page = editorPage(b, wakeLockStub)

        openFirstRecipe(page)
        page.locator("[data-testid=chef]").click()
        page.waitForFunction("() => window.__wakeLog.includes('request')")
        assertEquals(listOf("request"), wakeLog(page), "chef mode should take exactly one lock")

        page.keyboard().press("Escape")
        page.waitForFunction("() => window.__wakeLog.includes('release')")
        assertEquals(listOf("request", "release"), wakeLog(page), "leaving should give the lock back")

        // Through the menu, because Preferences replaced two items with one and that path is the
        // one a person actually walks.
        page.locator(".appbar__account wa-button").first().click()
        page.locator("[data-testid=open-preferences]").click()
        page.waitForSelector("[data-testid=wakelock]")
        // Preferences absorbed two dialogs, so all three sections have to be on this one page --
        // now split across two tabs, with General open first because that is where chef mode lives.
        assertThat(page.locator("wa-dialog[data-dialog=preferences] .prefsect")).hasCount(3)
        assertThat(page.locator("wa-dialog[data-dialog=preferences] wa-tab")).hasCount(2)

        // The password form is real but a tab away, so it must not be showing yet: a visible
        // password field under a tab labelled General would mean the split did not take.
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Password")))
            .not().isVisible()
        page.locator("wa-dialog[data-dialog=preferences] wa-tab[panel=security]").click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Password"))).isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Authorized apps")))
            .isVisible()
        assertEquals(
            1, page.locator("wa-dialog[data-dialog=preferences] [data-testid=save-password]").count(),
            "the password form should still be here, with its own submit",
        )

        // Back to General for the switch, which is what the rest of this test drives.
        page.locator("wa-dialog[data-dialog=preferences] wa-tab[panel=general]").click()
        assertThat(page.locator("[data-testid=wakelock]")).isVisible()
        // The switch graphic, reached through its part. Clicking the <wa-switch> host itself is
        // what a person does NOT do: the host box includes the hint line underneath, so a click at
        // its centre lands on explanatory text and toggles nothing. (The visible label does work —
        // that was checked while writing this.)
        page.locator("[data-testid=wakelock] [part~=control]").click()
        page.waitForFunction(
            "() => Alpine.${'$'}data(document.getElementById('app')).wakeLockPref === false")
        page.locator("wa-dialog[data-dialog=preferences] wa-button[slot=footer]").first().click()
        page.waitForFunction("() => Alpine.${'$'}data(document.getElementById('app')).dialog === null")

        page.locator("[data-testid=chef]").click()
        page.waitForFunction("() => document.getElementById('app').hasAttribute('data-chef')")
        assertEquals(
            listOf("request", "release"), wakeLog(page),
            "with the preference off, chef mode should not ask for a lock at all",
        )
        page.keyboard().press("Escape")

        // The preference is per browser and survives a reload — it describes this screen, not the
        // account, so it is not something the server was ever told.
        page.reload()
        page.waitForSelector(".rrow")
        assertEquals(
            false,
            page.evaluate("() => Alpine.${'$'}data(document.getElementById('app')).wakeLockPref"),
            "the switch should still be off after a reload",
        )
        page.close()
    }

    /** A recipe with no yield, so the meta line has a gap where a separator used to be printed. */
    private fun seedGappyRecipe() = runBlocking {
        RecipeRepository.upsert(
            UserRepository.findByUsername("tester")!!.id,
            ServerRecipe(
                id = "01A05100-0000-7000-8000-0000000000R3",
                name = "Aa Gappy Stew",
                lastModifiedDate = "2026-08-01T00:00:00.000Z",
                servings = 6,
                rating = 4,
                courseId = COURSE_ID,
                categoryIds = listOf(CATEGORY_ID),
            ),
        )
    }

    /**
     * The line under a recipe's name, and what is no longer above it.
     *
     * Four templates each carrying their own separator printed one for every field, present or
     * not, so a recipe with no yield read "Breads .  . Serves 6". And the categories used to sit
     * over the recipe as a row of outlined boxes, which is not what you want to see first.
     */
    @Test
    fun theMetaLineSkipsMissingFieldsAndNoChipsSitAboveTheRecipe() {
        val b = requireBrowser()
        seedGappyRecipe()
        val page = editorPage(b)

        page.locator(".rrow").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Aa Gappy Stew")
        ).click()
        page.waitForFunction(
            "() => Alpine.${'$'}data(document.getElementById('app')).current?.name === 'Aa Gappy Stew'")
        val meta = page.locator(".read__meta").textContent().trim()
        assertEquals("Breads \u00b7 Serves 6 \u00b7 \u2605\u2605\u2605\u2605", meta)

        assertEquals(0, page.locator(".read__chips").count(), "no chip row above the recipe")
        assertEquals(
            0, page.locator(".read wa-tag").count(),
            "the category was assigned; it should simply not be drawn as a box here",
        )
        page.close()
    }

    /** Enough recipes to overflow the list column on the 1400x900 test viewport. */
    private fun seedManyRecipes() = runBlocking {
        val uid = UserRepository.findByUsername("tester")!!.id
        ('A'..'T').forEachIndexed { i, c ->
            RecipeRepository.upsert(
                uid,
                ServerRecipe(
                    id = "01A05100-0000-7000-8000-0000000000%02d".format(i),
                    name = "$c Recipe number $i",
                    lastModifiedDate = "2026-08-01T00:00:00.000Z",
                ),
            )
        }
    }

    /** The name on whichever row currently holds focus. */
    private fun focusedRowName(page: Page) = page.evaluate(
        """() => document.activeElement?.closest('.rrow')
                 ?.querySelector('.rrow__name')?.textContent?.trim()"""
    )

    /**
     * Assigning a category from the editor must reach the API. The recipe list already returns
     * categoryIds, so this also feeds the sidebar counts.
     */
    @Test
    fun assigningACategoryPersists() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForFunction(
            """() => [...document.querySelectorAll('wa-select')]
                     .some(s => s.getAttribute('label') === 'Categories')"""
        )

        // Drive the component, then save through the app's own path.
        page.evaluate(
            """() => {
                 const d = Alpine.${'$'}data(document.getElementById('app'));
                 const cat = d.categories[0];
                 d.current.categoryIds = [cat.id];
                 d.touch();
               }"""
        )
        page.locator("[data-testid=save]").click()
        page.waitForFunction("() => !Alpine.${'$'}data(document.getElementById('app')).dirty")

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals(listOf(CATEGORY_ID), stored?.categoryIds)
        page.close()
    }

    /** Create, rename and delete a tag through the library manager. */
    @Test
    fun libraryManagerCreatesRenamesAndDeletes() {
        val b = requireBrowser()
        val page = editorPage(b)

        page.evaluate(
            """async () => {
                 const d = Alpine.${'$'}data(document.getElementById('app'));
                 d.newClassifier.tag = 'Weeknight';
                 await d.addClassifier('tag');
               }"""
        )
        page.waitForFunction("() => Alpine.${'$'}data(document.getElementById('app')).tags.some(t => t.name === 'Weeknight')")

        page.evaluate(
            """async () => {
                 const d = Alpine.${'$'}data(document.getElementById('app'));
                 const t = d.tags.find(x => x.name === 'Weeknight');
                 await d.renameClassifier('tag', t, 'Weeknight Dinner');
               }"""
        )
        page.waitForFunction("() => Alpine.${'$'}data(document.getElementById('app')).tags.some(t => t.name === 'Weeknight Dinner')")

        val renamed = runBlocking { LibraryRepository.listTags(UserRepository.findByUsername("tester")!!.id) }
        assertTrue(renamed.any { it.name == "Weeknight Dinner" }, "rename should have reached the API")

        page.evaluate(
            """async () => {
                 const d = Alpine.${'$'}data(document.getElementById('app'));
                 const t = d.tags.find(x => x.name === 'Weeknight Dinner');
                 d.askDeleteClassifier('tag', t);
                 await d.deleteClassifier();
               }"""
        )
        page.waitForFunction("() => !Alpine.${'$'}data(document.getElementById('app')).tags.length")

        val after = runBlocking { LibraryRepository.listTags(UserRepository.findByUsername("tester")!!.id) }
        assertTrue(after.none { it.name == "Weeknight Dinner" }, "delete should have reached the API")
        page.close()
    }

    /** Group rows (Categories, Courses, ...) are containers: selecting one must not filter. */
    @Test
    fun expandingAGroupDoesNotChangeTheFilter() {
        val b = requireBrowser()
        val page = editorPage(b)
        val title = page.locator("section[aria-label='Recipes'] .list__title")
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
        val b = requireBrowser()
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

    /**
     * Editing is modal, as it is in the Swift client. Before this, the rail stayed live behind an
     * open editor, so picking another category swapped the recipe list out from under the recipe
     * being edited -- and each new way out of an edit had to remember to ask about unsaved work.
     */
    @Test
    fun editingIsModalSoTheLibraryCannotBeReachedBehindIt() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        // The browser's own notion of modality, not a class we set.
        val isModal = page.evaluate(
            "() => document.querySelector('wa-dialog.editdlg').shadowRoot.querySelector('dialog').matches(':modal')"
        )
        assertEquals(true, isModal, "the editor should be a real modal dialog")

        // And the rail is genuinely unreachable: a hit test over a library row lands on the dialog.
        val treeIsBlocked = page.evaluate(
            """() => {
                 const item = document.querySelector('wa-tree-item[data-kind="category"]');
                 const r = item.getBoundingClientRect();
                 const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                 return !item.contains(hit) && hit !== item;
               }"""
        )
        assertEquals(true, treeIsBlocked, "the library rail should not be clickable behind the editor")
        page.close()
    }

    /**
     * Difficulty saved correctly but always rendered blank, which read as "saving doesn't work".
     * `wa-select.value` only accepts strings: x-model.number assigned a NUMBER, which the component
     * discards as null. The stored level then never appeared when the recipe was reopened.
     */
    @Test
    fun difficultySurvivesAReopenAndIsDisplayed() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        page.locator("wa-select[label=Difficulty]").evaluate(
            """el => { el.value = '4';
                       el.dispatchEvent(new Event('change', { bubbles: true, composed: true })); }"""
        )
        saveAndWait(page)

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals(4, stored?.difficulty, "the chosen difficulty should reach the API")

        // Reopen: the level must be visible, not silently dropped on the way back in.
        reopenEditor(page)
        val shown = page.locator("wa-select[label=Difficulty]").evaluate("el => el.value")
        assertEquals("4", shown, "the stored difficulty should be displayed when reopening the editor")
        page.close()
    }

    /**
     * Every level the shared Difficulty enum defines must be reachable. The first version offered
     * four options with invented labels, so "Hard" actually stored MEDIUM and two levels could not
     * be chosen at all -- a value the native clients would then render differently.
     */
    @Test
    fun difficultyOffersEveryLevelTheSharedEnumDefines() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        val values = page.locator("wa-select[label=Difficulty]").evaluate(
            "el => [...el.querySelectorAll('wa-option')].map(o => o.getAttribute('value')).join(',')"
        )
        assertEquals("0,1,2,3,4,5", values, "every Difficulty level should be selectable")
        page.close()
    }

    /**
     * `wa-hide` bubbles: every wa-select and wa-dropdown inside the editor raises it when its menu
     * closes. The dialog's close handler saw those too, so simply picking a course or difficulty
     * asked "Discard unsaved changes?".
     */
    @Test
    fun aDropdownInsideTheEditorDoesNotAskToDiscard() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        page.evaluate("() => { window.__confirms = []; window.confirm = m => { window.__confirms.push(m); return false; }; }")
        // Make it dirty, so a stray hide WOULD prompt if the handler were still listening broadly.
        page.locator(".titlefield").evaluate(
            """el => { el.value = 'Dirty';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        // Open and close a real dropdown inside the editor.
        page.locator("wa-select[label=Difficulty]").click()
        page.waitForTimeout(300.0)
        page.locator("wa-select[label=Difficulty] wa-option[value='3']").click()
        page.waitForTimeout(400.0)

        val prompts = page.evaluate("() => window.__confirms.length")
        assertEquals(0, prompts, "choosing from a dropdown should not ask to discard the edit")
        assertThat(page.locator(".edit")).isVisible()
        page.close()
    }

    /**
     * The detail column follows the list. It used to keep showing a recipe the middle column no
     * longer listed, so the two columns disagreed about where you were.
     */
    @Test
    fun changingTheLibraryFilterClearsARecipeThatIsNoLongerListed() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        assertThat(page.locator(".read")).isVisible()

        // The seeded recipe is not a favourite, so this filter cannot contain it.
        page.locator("wa-tree-item[data-kind=favorites]").click()
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).current")
        assertThat(page.locator(".read")).hasCount(0)
        page.close()
    }

    /* -------------------------------------------------------- web import -- */

    /**
     * The client half of the import, which the server test can't see: what comes back has to land in
     * the editor as a DRAFT — open for review, not written to the library. A mis-pasted URL should
     * cost the user nothing, so nothing may be saved until they say so.
     */
    @Test
    fun anImportedRecipeOpensForReviewWithoutBeingSaved() {
        val b = requireBrowser()
        val page = editorPage(b)
        val before = runBlocking {
            RecipeRepository.listForSync(UserRepository.findByUsername("tester")!!.id, null, null, 100).recipes.size
        }

        // By test id, not "the first dropdown in this column": the column has a sort menu now, and
        // `.first()` quietly opened that one instead of failing.
        page.locator("[data-testid=new-recipe-more]").click()
        page.locator("[data-testid=import-from-web]").click()
        // Wait on a control INSIDE the dialog, not the <wa-dialog> host: the host has no box of its
        // own (the panel lives in its shadow root), so waitForSelector's visibility check never
        // passes on it.
        page.waitForSelector("[data-testid=import-url]")

        page.locator("[data-testid=import-url]").click()
        page.keyboard().type("http://127.0.0.1:$sitePort/recipe")
        page.locator("[data-testid=run-import]").click()
        // Wait for either outcome, so a failed import reports the server's message instead of
        // timing out on a dialog that is never going to open.
        page.waitForFunction(
            """() => { const d = Alpine.${'$'}data(document.getElementById('app'));
                       return !!d.importError || !!d.current; }"""
        )
        assertEquals(
            "", page.evaluate("() => Alpine.\$data(document.getElementById('app')).importError"),
            "the import should have succeeded",
        )

        // It opens in the editor, filled in from the page.
        page.waitForSelector("[data-testid=save]")
        page.waitForFunction(
            "() => Alpine.\$data(document.getElementById('app')).current?.name === 'Imported Cornbread'"
        )
        assertTrue(
            page.evaluate("() => Alpine.\$data(document.getElementById('app')).isDraft") as Boolean,
            "an imported recipe should be a draft until it is saved",
        )

        // And the library is untouched.
        val after = runBlocking {
            RecipeRepository.listForSync(UserRepository.findByUsername("tester")!!.id, null, null, 100).recipes
        }
        assertEquals(before, after.size, "import must not save anything")
        assertTrue(after.none { it.name == "Imported Cornbread" })
        page.close()
    }

    /** Saving the draft is what turns it into a recipe — and it only happens on purpose. */
    @Test
    fun savingAnImportedDraftAddsItToTheLibrary() {
        val b = requireBrowser()
        val page = editorPage(b)

        // By test id, not "the first dropdown in this column": the column has a sort menu now, and
        // `.first()` quietly opened that one instead of failing.
        page.locator("[data-testid=new-recipe-more]").click()
        page.locator("[data-testid=import-from-web]").click()
        page.locator("[data-testid=import-url]").click()
        page.keyboard().type("http://127.0.0.1:$sitePort/recipe")
        page.locator("[data-testid=run-import]").click()
        // Wait for either outcome, so a failed import reports the server's message instead of
        // timing out on a dialog that is never going to open.
        page.waitForFunction(
            """() => { const d = Alpine.${'$'}data(document.getElementById('app'));
                       return !!d.importError || !!d.current; }"""
        )
        assertEquals(
            "", page.evaluate("() => Alpine.\$data(document.getElementById('app')).importError"),
            "the import should have succeeded",
        )
        page.waitForSelector("[data-testid=save]")

        page.locator("[data-testid=save]").click()
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).dirty")

        val stored = runBlocking {
            RecipeRepository.listForSync(UserRepository.findByUsername("tester")!!.id, null, null, 100).recipes
        }
        val imported = stored.singleOrNull { it.name == "Imported Cornbread" }
        assertTrue(imported != null, "the saved draft should be in the library: ${stored.map { it.name }}")
        assertEquals("8 wedges", imported.yield)
        assertEquals(2, imported.ingredients?.size)
        page.close()
    }

    /* ------------------------------------------------------ shopping lists -- */

    private fun seedList(vararg items: Pair<String, Boolean>): ServerShoppingList = runBlocking {
        val user = UserRepository.findByUsername("tester")!!
        val list = ServerShoppingList(
            id = LIST_ID,
            name = "Groceries",
            isFreeform = false,
            contentsForList = items.mapIndexed { i, (text, done) ->
                ShoppingListListContents(id = "s$i", text = text, isCompleted = done)
            },
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        )
        (ShoppingListRepository.save(user.id, list) as ShoppingListRepository.SaveResult.Saved).list
    }

    private fun storedList(): ServerShoppingList? = runBlocking {
        ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, LIST_ID)
    }

    /** Shopping lists are a pane in the editor now, not a link back to the classic page. */
    @Test
    fun shoppingListsOpenInsideTheEditor() {
        val b = requireBrowser()
        seedList("Milk" to false, "Eggs" to false)
        val page = editorPage(b)

        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.waitForSelector("section[aria-label='Shopping lists'] .rrow")
        page.locator("section[aria-label='Shopping lists'] .rrow").first().click()
        page.waitForSelector(".slist")

        assertThat(page.locator(".sitem")).hasCount(2)
        page.close()
    }

    /**
     * Enter adds an item. wa-input keeps its real <input> in shadow DOM, so a <form> never sees an
     * implicit submit -- the handler is on the input itself, and this is what proves it.
     */
    @Test
    fun pressingEnterAddsAnItemAndAutosaves() {
        val b = requireBrowser()
        seedList("Milk" to false)
        val page = editorPage(b)
        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.locator("section[aria-label='Shopping lists'] .rrow").first().click()
        page.waitForSelector(".slist")

        page.locator(".slist__add wa-input").click()
        page.keyboard().type("Butter")
        page.keyboard().press("Enter")

        // Autosave is debounced; wait for it to settle rather than for a fixed delay.
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).listDirty")
        page.waitForTimeout(300.0)

        val texts = storedList()?.contentsForList.orEmpty().map { it.text }
        assertTrue(texts.contains("Butter"), "Enter should have added and saved the item: $texts")
        page.close()
    }

    /**
     * The split button's menu is the only way to make the second list shape, so it is the only
     * thing standing between the app and "checklists only".
     */
    @Test
    fun theSplitButtonMenuCreatesAMarkdownList() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.waitForSelector("section[aria-label='Shopping lists'] wa-button-group")

        page.locator("section[aria-label='Shopping lists'] wa-dropdown wa-button").click()
        page.locator("[data-testid=new-markdown-list]").click()

        // A Markdown list is a text area and no item rows -- that is the whole difference.
        page.waitForSelector(".slist wa-textarea.freeform")
        assertThat(page.locator(".slist .slist__add")).hasCount(0)

        val stored = runBlocking {
            ShoppingListRepository.list(UserRepository.findByUsername("tester")!!.id)
        }
        assertEquals(1, stored.size)
        assertTrue(stored.single().isFreeform == true, "the created list should be freeform")
        page.close()
    }

    /**
     * Typing in the text area has to save, and it has to save without inventing a checklist.
     *
     * The second half is the part that was wrong: the app normalised every list it opened to have
     * BOTH contents fields, so the first keystroke in a Markdown list wrote `contentsForList: []`
     * over a column the server deliberately leaves NULL. Every other client reads that column, so
     * "no checklist" quietly became "an empty checklist".
     */
    @Test
    fun editingAMarkdownListSavesAndLeavesTheChecklistColumnNull() {
        val b = requireBrowser()
        val list = runBlocking {
            val user = UserRepository.findByUsername("tester")!!
            val l = ServerShoppingList(
                id = LIST_ID, name = "Hardware", isFreeform = true,
                contentsForFreeform = "## Hardware store\n",
                lastModifiedDate = "2026-08-01T00:00:00.000Z",
            )
            (ShoppingListRepository.save(user.id, l) as ShoppingListRepository.SaveResult.Saved).list
        }
        assertEquals(null, list.contentsForList, "seeded row should start with a NULL checklist")

        val page = editorPage(b)
        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.locator("section[aria-label='Shopping lists'] .rrow").first().click()
        page.waitForSelector(".slist wa-textarea.freeform")

        page.locator(".slist wa-textarea.freeform").click()
        page.keyboard().type("- 2x4 lumber")

        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).listDirty")
        page.waitForTimeout(300.0)

        val stored = storedList()
        assertTrue(
            stored?.contentsForFreeform.orEmpty().contains("2x4 lumber"),
            "the Markdown text should have saved: ${stored?.contentsForFreeform}",
        )
        assertEquals(null, stored?.contentsForList, "a Markdown list must not grow a checklist")
        page.close()
    }

    /**
     * The reason this pane resolves conflicts at all. A shopping list is worked by several people
     * at once, so a check-off landing between this browser's load and its save must not be undone
     * by it -- and the browser's own edit must not be lost either. Both survive because the save
     * falls back to the shared merge on 409 instead of overwriting.
     */
    @Test
    fun aConcurrentCheckOffIsMergedRatherThanOverwritten() {
        val b = requireBrowser()
        val seeded = seedList("Milk" to false, "Eggs" to false)
        val page = editorPage(b)
        page.locator("wa-tree-item[data-kind=shopping]").click()
        page.locator("section[aria-label='Shopping lists'] .rrow").first().click()
        page.waitForSelector(".slist")

        // Another client ticks off "Milk" while this browser holds the copy it loaded.
        runBlocking {
            val user = UserRepository.findByUsername("tester")!!
            ShoppingListRepository.save(
                user.id,
                seeded.copy(
                    contentsForList = seeded.contentsForList.orEmpty().map {
                        if (it.text == "Milk") it.copy(isCompleted = true) else it
                    },
                    lastModifiedDate = "2026-08-02T00:00:00.000Z",
                    baseRevision = seeded.revision,
                ),
            )
        }

        // This browser renames the OTHER item and saves against its now-stale revision.
        page.locator(".sitem").nth(1).locator("wa-input").evaluate(
            """el => { el.value = 'Free-range eggs';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).listDirty")
        page.waitForTimeout(400.0)

        val stored = storedList()?.contentsForList.orEmpty().associateBy { it.text }
        assertEquals(true, stored["Milk"]?.isCompleted,
            "the other client's check-off must survive this browser's save")
        assertTrue(stored.containsKey("Free-range eggs"),
            "this browser's rename must survive too: ${stored.keys}")
        page.close()
    }

    /* -------------------------------------------------------- editor parity -- */

    /**
     * `selection="leaf"` makes any childless node selectable, so a group with nothing in it became
     * a selectable row that filtered to nothing. The seed has no tags, so Tags is the empty one.
     */
    @Test
    fun anEmptyLibraryGroupIsNotSelectable() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.waitForSelector("wa-tree-item[data-group]")

        val tags = page.locator("wa-tree-item[data-group]").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Tags")).first()
        assertThat(tags).hasAttribute("disabled", java.util.regex.Pattern.compile(".*"))

        // Categories has one, so it stays usable.
        val cats = page.locator("wa-tree-item[data-group]").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Categories")).first()
        assertEquals(false, cats.evaluate("el => el.hasAttribute('disabled')"))
        page.close()
    }

    /** Both flags were settable everywhere except the editor. */
    @Test
    fun favoriteAndWantToMakeCanBeSetWhileEditing() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        // Both boxes, not just the first: they are adjacent near-identical blocks, which is exactly
        // the copy-paste slip this test's name promises to catch.
        for (i in 0..1) {
            page.locator(".fieldrow--flags wa-checkbox").nth(i).evaluate(
                """el => { el.checked = true;
                           el.dispatchEvent(new Event('change', { bubbles: true, composed: true })); }"""
            )
        }
        saveAndWait(page)

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals(true, stored?.isFavorite, "Favorite should be settable from the editor")
        assertEquals(true, stored?.wantToMake, "Want to Make should be settable from the editor")
        page.close()
    }

    /** A tag can be invented mid-recipe without leaving the editor for the library manager. */
    @Test
    fun aTagCanBeCreatedFromInsideTheEditor() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        page.locator("[data-testid=new-tag]").click()
        page.waitForSelector("[data-testid=create-tag]")
        page.locator("wa-dialog[label='New tag'] wa-input").evaluate(
            """el => { el.value = 'Sheet Pan';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        page.locator("[data-testid=create-tag]").click()
        page.waitForFunction(
            "() => Alpine.\$data(document.getElementById('app')).tags.some(t => t.name === 'Sheet Pan')")

        page.locator("[data-testid=save]").click()
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).dirty")

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals(1, stored?.tagIds?.size, "the new tag should be attached to the recipe")
        page.close()
    }

    /* ------------------------------------------- notes, variations, times -- */

    private fun openEditorFor(page: Page) {
        openFirstRecipe(page)
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")
    }

    /**
     * Opens the first recipe in the list.
     *
     * Nothing opens on its own any more -- the app lands on the empty state by design -- so a test
     * whose subject is what happens *around* an open recipe now has to say which one it means.
     * That the landing state is empty is its own test; here it is a precondition, not the subject.
     */
    private fun openFirstRecipe(page: Page) {
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
    }

    /** Save writes and closes the dialog, as a modal's primary action should. */
    private fun saveAndWait(page: Page) {
        page.locator("[data-testid=save]").click()
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).dirty")
        page.waitForSelector(".read")
    }

    /** For tests that keep editing after a save; the dialog is closed by then. */
    private fun reopenEditor(page: Page) {
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")
    }

    private fun stored(): ServerRecipe? = runBlocking {
        RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
    }

    /** Adding a note through the real controls, and removing it again, both have to persist. */
    @Test
    fun aNoteCanBeAddedAndRemovedThroughTheEditor() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)

        page.locator("[data-testid=details-notes]").evaluate("el => el.open = true")
        page.locator("[data-testid=add-note]").click()
        page.waitForSelector(".subblock")
        page.locator(".subblock__head wa-input").first().evaluate(
            """el => { el.value = 'Make ahead';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        saveAndWait(page)
        assertEquals(listOf("Make ahead"), stored()?.notes.orEmpty().map { it.title })

        reopenEditor(page)
        page.locator("[data-testid=details-notes]").evaluate("el => el.open = true")
        page.locator(".subblock__head wa-button").first().click()
        saveAndWait(page)
        assertTrue(stored()?.notes.orEmpty().isEmpty(), "removing the note should persist")
        page.close()
    }

    /** Variations and preparation times use the same machinery; this checks they're wired to it. */
    @Test
    fun variationsAndPreparationTimesPersist() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        page.evaluate(
            """() => {
                 const st = Alpine.${'$'}data(document.getElementById('app'));
                 st.addSubRow('variations', { variationName: 'Mini', text: 'Muffin tin.' });
                 st.addSubRow('preparationTimes', { type: 'Chill', timeString: '2 hr' });
               }"""
        )
        saveAndWait(page)

        val r = stored()
        assertEquals(listOf("Mini"), r?.variations.orEmpty().map { it.variationName })
        assertEquals(listOf("Chill" to "2 hr"), r?.preparationTimes.orEmpty().map { it.type to it.timeString })
        page.close()
    }

    /**
     * "0 g of trans fat" is a claim about the food; an empty field is not. They must not collapse
     * into each other, which is the whole reason the editor writes null rather than 0 for a blank.
     */
    @Test
    fun nutritionKeepsZeroButTreatsAnEmptyFieldAsUnknown() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        page.locator("[data-testid=details-nutrition]").evaluate("el => el.open = true")

        page.evaluate(
            """() => {
                 const st = Alpine.${'$'}data(document.getElementById('app'));
                 st.setNutrition('transFat', '0');
                 st.setNutrition('protein', '12.5');
                 st.setNutrition('iron', '');
               }"""
        )
        saveAndWait(page)

        val n = stored()?.nutrition
        assertEquals(0.0, n?.transFat, "an explicit zero must be kept")
        assertEquals(12.5, n?.protein)
        assertEquals(null, n?.iron, "a blank field means unknown, not zero")
        page.close()
    }

    /** Clearing the last value should drop the record, not leave an empty one carrying an id. */
    @Test
    fun clearingEveryNutritionFieldRemovesTheRecord() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        page.evaluate(
            """() => {
                 const st = Alpine.${'$'}data(document.getElementById('app'));
                 st.setNutrition('protein', '10');
                 for (const g of st.nutritionGroups) for (const f of g.fields)
                   st.setNutrition(f.key, '', !!f.text);
               }"""
        )
        saveAndWait(page)
        assertEquals(null, stored()?.nutrition, "an emptied nutrition record should not be stored")
        page.close()
    }

    /**
     * Guards against the editor and the model drifting apart: every field NutritionInformation
     * defines gets an input, because both the editor and the reading view are generated from one
     * list in app.js.
     */
    @Test
    fun theNutritionEditorCoversEveryFieldInTheModel() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        page.locator("[data-testid=details-nutrition]").evaluate("el => el.open = true")

        // Counted from the model itself, not a literal: both sides of the old assertion derived
        // from app.js, so a field added to NutritionInformation and forgotten in the JS list
        // left this green while the field was uneditable and invisible.
        val modelFields =
            com.enuvro.saltykmp.db.model.NutritionInformation.serializer().descriptor.elementsCount - 1
        assertEquals(18, modelFields, "guard: update this test if NutritionInformation changes shape")
        assertThat(page.locator("[data-testid=details-nutrition] wa-input")).hasCount(modelFields)
        assertThat(page.locator("[data-testid=details-nutrition] .nutgroup")).hasCount(4)
        page.close()
    }

    /* ---------------------------------------------------------------- image -- */

    /** Puts real PNG bytes through the actual <input type=file>, as a file picker would. */
    private fun stageImage(page: Page, color: String) {
        page.evaluate(
            """async (color) => {
                 const canvas = document.createElement('canvas');
                 canvas.width = 64; canvas.height = 48;
                 const ctx = canvas.getContext('2d');
                 ctx.fillStyle = color; ctx.fillRect(0, 0, 64, 48);
                 const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
                 const input = document.getElementById('imgfile');
                 const dt = new DataTransfer();
                 dt.items.add(new File([blob], 'pic.png', { type: 'image/png' }));
                 input.files = dt.files;
                 input.dispatchEvent(new Event('change', { bubbles: true }));
               }""",
            color,
        )
        page.waitForFunction("() => Alpine.\$data(document.getElementById('app')).pendingImageFile !== null")
    }

    /**
     * An image is staged and only uploaded on save, so it behaves like every other edit in the
     * dialog. Uploading on pick would put one control outside the Save/Revert contract.
     */
    @Test
    fun anImageUploadsOnSaveAndIsServedBack() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        stageImage(page, "#c4844a")

        // Nothing has reached the server yet.
        assertEquals(null, stored()?.imageFilename, "picking a file must not upload on its own")

        saveAndWait(page)
        val filename = stored()?.imageFilename
        assertTrue(filename != null && filename.endsWith(".png"),
            "the extension comes from the bytes: $filename")
        assertTrue(stored()?.lastModifiedImageDate != null,
            "the image stamp is what lets other clients notice the change")

        // And the bytes come back.
        val status = page.evaluate(
            "async (fn) => (await fetch('/api/recipes/images/' + fn)).status", filename)
        assertEquals(200, status)
        page.close()
    }

    /** Cancel has to undo a staged image like any other pending edit. */
    @Test
    fun cancellingDropsAStagedImage() {
        val b = requireBrowser()
        val page = editorPage(b)
        // Cancel confirms before throwing work away; Playwright dismisses dialogs unless told.
        page.onDialog { it.accept() }
        openEditorFor(page)
        stageImage(page, "#4a84c4")

        page.locator("[data-testid=cancel]").click()
        page.waitForFunction("() => Alpine.\$data(document.getElementById('app')).pendingImageFile === null")

        assertEquals(null, stored()?.imageFilename, "a reverted image must never be uploaded")
        page.close()
    }

    /** Clearing stages a removal; the file and the filename go on save, with a fresh stamp. */
    @Test
    fun clearingAnImageRemovesItOnSave() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        stageImage(page, "#c4844a")
        saveAndWait(page)
        val filename = stored()?.imageFilename
        assertTrue(filename != null)

        reopenEditor(page)
        page.locator("[data-testid=clear-image]").click()
        page.waitForFunction("() => Alpine.\$data(document.getElementById('app')).pendingImageRemoval")
        assertEquals(filename, stored()?.imageFilename, "clearing must not delete before save")

        saveAndWait(page)
        assertEquals(null, stored()?.imageFilename, "the filename should be cleared")
        assertTrue(stored()?.lastModifiedImageDate != null,
            "a removal is stamped too, so other clients see it")
        val status = page.evaluate(
            "async (fn) => (await fetch('/api/recipes/images/' + fn)).status", filename)
        assertEquals(404, status, "the stored file should be gone, not just unreferenced")
        page.close()
    }

    /** A file the server would reject is refused here first, with a specific reason. */
    @Test
    fun aNonImageIsRefusedBeforeUploading() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)
        page.evaluate(
            """() => {
                 const input = document.getElementById('imgfile');
                 const dt = new DataTransfer();
                 dt.items.add(new File([new Blob(['%PDF-1.4'])], 'notes.pdf', { type: 'application/pdf' }));
                 input.files = dt.files;
                 input.dispatchEvent(new Event('change', { bubbles: true }));
               }"""
        )
        page.waitForTimeout(400.0)
        assertEquals(false, page.evaluate(
            "() => Alpine.\$data(document.getElementById('app')).pendingImageFile !== null"),
            "a PDF should never be staged")
        page.close()
    }

    /**
     * L5: ingredients and directions are the ONLY payload fields not carried by save()'s
     * `...this.current` spread — they are rebuilt from separate row state and folded back in. Break
     * that mapping and every other test still passes while a browser save blanks both on every
     * device. This also pins the untouched fields, so the spread itself can't silently drop one.
     */
    @Test
    fun savingKeepsIngredientsDirectionsAndTheFieldsThisScreenDidNotTouch() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)

        page.locator(".rows--plain .row__edit").first().evaluate(
            """el => { el.value = 'Dry mix';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        saveAndWait(page)

        val r = stored()
        assertEquals(
            listOf("Dry mix", "1 1/2 cups yellow cornmeal", "1/2 cup all-purpose flour"),
            r?.ingredients.orEmpty().map { it.text },
            "the edited ingredient and its neighbours must all survive",
        )
        assertEquals(
            listOf("Heat the skillet.", "Bake", "Bake 22 minutes."),
            r?.directions.orEmpty().map { it.text },
            "directions are rebuilt the same way and must survive untouched",
        )
        assertEquals(true, r?.ingredients?.get(0)?.isHeading, "row flags must round-trip")
        assertEquals(true, r?.ingredients?.get(1)?.isMain)

        // Fields the editor never touched in this run.
        assertEquals(4, r?.rating)
        assertEquals("8 servings", r?.yield)
        assertEquals(8, r?.servings)
        assertEquals(COURSE_ID, r?.courseId)
        assertEquals(listOf(CATEGORY_ID), r?.categoryIds)
        page.close()
    }

    /**
     * Both exits must actually close the dialog. Nothing asserted this before: every test waited
     * for `.read`, which renders whenever mode is "read" regardless of whether the modal is still
     * covering it -- so a dialog stuck open would have left the whole suite green.
     *
     * Asserted on the component's own state, not Playwright visibility: a wa-dialog host has no
     * box of its own (the panel lives in the top layer), so `isVisible()` reports false even while
     * the modal is up -- which would make the obvious version of this test vacuous.
     *
     * Scope is deliberately just the closing. That the two buttons write or discard is covered by
     * editingARecipePersistsThroughTheApi and cancellingDropsAStagedImage.
     */
    @Test
    fun cancelAndSaveBothCloseTheDialog() {
        val b = requireBrowser()
        val page = editorPage(b)
        page.onDialog { it.accept() }
        val isOpen = "() => document.querySelector('wa-dialog.editdlg').hasAttribute('open')"
        val isShut = "() => !document.querySelector('wa-dialog.editdlg').hasAttribute('open')"

        openEditorFor(page)
        page.waitForFunction(isOpen)

        page.locator("[data-testid=cancel]").click()
        page.waitForFunction(isShut)
        assertEquals("Skillet Cornbread", stored()?.name, "Cancel must not write anything")

        openEditorFor(page)
        page.waitForFunction(isOpen)
        page.locator("[data-testid=save]").click()
        page.waitForFunction(isShut)
        page.close()
    }

    /**
     * The sort fixture: three recipes whose four orderings are all DIFFERENT.
     *
     * That is the whole point of the dates below. If name, created, modified and prepared produced
     * even partly the same sequence, a picker wired to the wrong field -- or one that quietly fell
     * back to the name comparator -- would still pass. Every date is explicit because upsert
     * defaults a missing createdDate to *now*, which would put the fixture recipe's "created" at
     * today and make the expected order depend on the day the suite runs.
     *
     *   name     asc: Apple,    Skillet,  Zucchini
     *   created  asc: Zucchini, Apple,    Skillet
     *   modified asc: Skillet,  Zucchini, Apple
     *   prepared asc: Apple,    Zucchini, Skillet (never made, so last in BOTH directions)
     */
    private fun seedSortFixture() = runBlocking {
        val userId = UserRepository.findByUsername("tester")!!.id
        // The fixture recipe keeps its name and its never-been-made state; only its dates move.
        val cornbread = RecipeRepository.getById(userId, RECIPE_ID)!!
        RecipeRepository.upsert(
            userId,
            cornbread.copy(
                createdDate = "2026-03-01T00:00:00.000Z",
                lastModifiedDate = "2026-01-01T00:00:00.000Z",
            ),
        )
        RecipeRepository.upsert(
            userId,
            ServerRecipe(
                id = "01A05100-0000-7000-8000-0000000000S2",
                name = "Apple Pie",
                createdDate = "2026-02-01T00:00:00.000Z",
                lastModifiedDate = "2026-03-01T00:00:00.000Z",
                lastPrepared = "2026-04-01T00:00:00.000Z",
            ),
        )
        RecipeRepository.upsert(
            userId,
            ServerRecipe(
                id = "01A05100-0000-7000-8000-0000000000S3",
                name = "Zucchini Bread",
                createdDate = "2026-01-01T00:00:00.000Z",
                lastModifiedDate = "2026-02-01T00:00:00.000Z",
                lastPrepared = "2026-05-01T00:00:00.000Z",
            ),
        )
    }

    /** The recipe names as the list column is currently showing them, top to bottom. */
    private fun listedNames(page: Page): List<String> =
        page.locator(".rrow__name").allTextContents().map { it.trim() }

    /** Opens the sort menu and picks one item; both a field and a direction need a fresh open. */
    private fun sortPick(page: Page, selector: String) {
        page.locator("[data-testid=sort-menu] wa-button").click()
        page.locator("[data-testid=sort-menu] $selector").click()
    }

    private fun sortField(page: Page, field: String) = sortPick(page, "[data-sort-field=$field]")

    private fun sortDir(page: Page, dir: String) = sortPick(page, "[data-sort-dir=$dir]")

    /**
     * Every sort field orders the list, in both directions.
     *
     * The four fields are checked against four DIFFERENT expected sequences (see seedSortFixture),
     * so this fails if the picker reaches the wrong comparator, and the descending half is checked
     * separately because direction is applied by negating the comparator rather than by a second
     * table of them -- a mistake there shows up in one direction only.
     */
    @Test
    fun everySortFieldOrdersTheListInBothDirections() {
        val b = requireBrowser()
        seedSortFixture()
        val page = editorPage(b)

        val apple = "Apple Pie"
        val skillet = "Skillet Cornbread"
        val zucchini = "Zucchini Bread"

        // Name ascending is the default, so this also pins the out-of-the-box order.
        assertEquals(listOf(apple, skillet, zucchini), listedNames(page))

        sortDir(page, "desc")
        assertEquals(listOf(zucchini, skillet, apple), listedNames(page))

        sortField(page, "created")
        sortDir(page, "asc")
        assertEquals(listOf(zucchini, apple, skillet), listedNames(page))
        sortDir(page, "desc")
        assertEquals(listOf(skillet, apple, zucchini), listedNames(page))

        sortField(page, "modified")
        sortDir(page, "asc")
        assertEquals(listOf(skillet, zucchini, apple), listedNames(page))
        sortDir(page, "desc")
        assertEquals(listOf(apple, zucchini, skillet), listedNames(page))

        page.close()
    }

    /**
     * "Last Made" keeps never-made recipes at the END in both directions, and says so in the row.
     *
     * Ascending would otherwise open on every recipe that has no date at all, which is noise for a
     * sort that exists to answer "what have I cooked lately" -- the CMP and Swift apps park them
     * last for the same reason. The subtitle swap is what stops the resulting order from looking
     * arbitrary: without it, nothing on screen explains the sequence or the block at the bottom.
     */
    @Test
    fun lastMadeSortsNeverMadeRecipesLastAndShowsTheDate() {
        val b = requireBrowser()
        seedSortFixture()
        val page = editorPage(b)

        sortField(page, "prepared")
        sortDir(page, "asc")
        assertEquals(
            listOf("Apple Pie", "Zucchini Bread", "Skillet Cornbread"), listedNames(page),
            "ascending: oldest made first, never-made last",
        )

        sortDir(page, "desc")
        assertEquals(
            listOf("Zucchini Bread", "Apple Pie", "Skillet Cornbread"), listedNames(page),
            "descending: newest made first, never-made STILL last",
        )

        // The row explains the ordering it is part of.
        assertThat(page.locator(".rrow").last().locator(".rrow__sub")).hasText("Never made")
        assertThat(page.locator(".rrow").first().locator(".rrow__sub")).containsText("Made ")

        // And gives the line back when the sort no longer needs it.
        sortField(page, "name")
        assertThat(page.locator(".rrow__sub")).hasCount(0)
        page.close()
    }

    /**
     * The chosen sort survives a reload, and the menu shows which one is in force.
     *
     * Both halves have failed before in this app for the same reason: `checked` is a property on a
     * custom element, and an Alpine binding that removes the attribute instead of writing the
     * value leaves a menu that works but can never say what it is doing.
     */
    @Test
    fun theChosenSortIsRememberedAndTicked() {
        val b = requireBrowser()
        seedSortFixture()
        val page = editorPage(b)

        sortField(page, "created")
        sortDir(page, "desc")
        assertEquals(listOf("Skillet Cornbread", "Apple Pie", "Zucchini Bread"), listedNames(page))

        page.reload()
        page.waitForSelector(".rrow")
        assertEquals(
            listOf("Skillet Cornbread", "Apple Pie", "Zucchini Bread"), listedNames(page),
            "the sort is a preference, so it should outlive the page",
        )

        // Choosing the sort ALREADY in force must leave it ticked: wa-dropdown flips a checkbox
        // item's `checked` on every selection, and the no-op case is the one where the binding has
        // nothing to re-render and the flip would otherwise stand.
        sortField(page, "created")
        assertEquals(
            listOf("Skillet Cornbread", "Apple Pie", "Zucchini Bread"), listedNames(page),
            "re-picking the current field must not disturb the order",
        )

        page.locator("[data-testid=sort-menu] wa-button").click()
        val ticked = page.evaluate(
            """() => [...document.querySelectorAll('[data-testid=sort-menu] wa-dropdown-item')]
                   .filter(i => i.checked).map(i => i.textContent.trim())"""
        ) as List<*>
        assertEquals(
            listOf("Date Created", "Descending Newest first"), ticked.map { it.toString().replace(Regex("\\s+"), " ") },
            "exactly the field and the direction in force should be ticked",
        )
        page.close()
    }

    /**
     * The app lands on the empty state, and deleting a recipe returns to it.
     *
     * Opening the app used to open the first row of a list sorted by last-modified: not the first
     * or last recipe on screen, not the one you had open before -- just whichever was edited most
     * recently, which is why it read as arbitrary. Deleting used to jump to that same arbitrary
     * next recipe, which also hid the fact that anything had been deleted.
     *
     * The compact half of this was never in question (the auto-open was gated on a wide screen);
     * the empty state is now what both widths land on.
     */
    @Test
    fun theAppLandsOnNoSelectionAndADeleteReturnsToIt() {
        val b = requireBrowser()
        seedSecondRecipe()
        val page = editorPage(b)

        val emptyState = "section[aria-label='Recipe'] .empty-state"   // the shopping pane has one too
        assertThat(page.locator(emptyState)).isVisible()
        assertThat(page.locator(".read")).hasCount(0)
        assertThat(page.locator(".rrow--on")).hasCount(0)
        assertEquals(
            null, page.evaluate("() => Alpine.\$data(document.getElementById('app')).selectedId"),
            "nothing should be selected on arrival",
        )

        openFirstRecipe(page)
        assertThat(page.locator(".rrow--on")).hasCount(1)

        page.locator("[data-testid=recipe-more]").click()
        page.locator("[data-testid=delete-recipe]").click()
        page.locator("[data-testid=confirm-delete]").click()

        page.waitForSelector(emptyState)
        assertThat(page.locator(".read")).hasCount(0)
        // The other recipe is still there -- the point is that we did not fall into it.
        assertThat(page.locator(".rrow")).hasCount(1)
        assertThat(page.locator(".rrow--on")).hasCount(0)
        page.close()
    }

    /**
     * The sort menu is operable from the keyboard, and picking that way actually sorts.
     *
     * wa-dropdown activates an item from Enter by calling its own selection path directly -- no DOM
     * click is dispatched -- so a menu wired with per-item click handlers ticks the checkbox and
     * reorders nothing. That is the quietest kind of broken: the menu looks like it worked. Both
     * routes emit `wa-select`, which is what the app listens to, and this walks the route that has
     * no click in it at all.
     */
    @Test
    fun theSortMenuWorksFromTheKeyboard() {
        val b = requireBrowser()
        seedSortFixture()
        val page = editorPage(b)

        page.locator("[data-testid=sort-menu] wa-button").click()

        // Arrow down to "Date Created" the way a keyboard user reaches it, wherever the menu put
        // its active item on opening.
        val target = page.locator("[data-testid=sort-menu] [data-sort-field=created]")
        var presses = 0
        while (presses < 8 && target.evaluate("el => !!el.active") != true) {
            page.keyboard().press("ArrowDown")
            presses++
        }
        assertTrue(presses < 8, "the arrow keys should reach the Date Created item")

        page.keyboard().press("Enter")
        assertEquals(
            listOf("Zucchini Bread", "Apple Pie", "Skillet Cornbread"), listedNames(page),
            "Enter on the focused item must sort, not just tick it",
        )
        page.close()
    }

    /**
     * A toast raised while a dialog is open used to render UNDERNEATH it. wa-dialog is a native
     * <dialog> opened with showModal(), so the modal and its backdrop paint in the browser's top
     * layer, and the hand-rolled `position: fixed` stack this replaced could not reach that at any
     * z-index. That covered most of what the app has to say -- user created, app removed, tag
     * added, image rejected, every library rename are all raised from inside a dialog. <wa-toast>
     * shows itself as a popover, which puts it in the top layer too, above a modal opened earlier.
     *
     * Being in the top layer is therefore the whole assertion, and `:popover-open` is how you ask.
     * Two other ways of putting it do NOT work, both tried:
     *   - a hit test (elementFromPoint) reports the dialog, because everything outside an open
     *     modal is `inert` and inert elements are not hit-tested. That says nothing about paint
     *     order -- the toast is drawn on top and still not clickable, which is a real if minor
     *     consequence: its close button and hover-to-pause are dead while a modal is up.
     *   - isVisible() is vacuous here for the reason cancelAndSaveBothCloseTheDialog gives: a
     *     popover/dialog host has no box of its own.
     */
    @Test
    fun aToastRaisedFromInsideADialogIsInTheTopLayer() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)

        // Adding a tag from inside the editor closes its own little dialog but leaves the editor
        // open, so the toast lands over a modal -- which is the case that used to be invisible.
        page.locator("[data-testid=new-tag]").click()
        page.locator("wa-dialog[label='New tag'] wa-input").evaluate(
            """el => { el.value = 'Weeknight';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        page.locator("[data-testid=create-tag]").click()

        val toast = page.locator("wa-toast wa-toast-item")
        toast.waitFor()
        assertThat(toast).containsText("Added Weeknight")
        assertTrue(
            page.evaluate("() => document.querySelector('wa-dialog.editdlg').hasAttribute('open')") as Boolean,
            "the editor dialog must still be open, or this proves nothing about stacking",
        )

        // In the top layer, and not by being tucked inside the dialog: a plain fixed-position stack
        // in the ordinary document -- what this replaced -- can satisfy neither half.
        val placement = page.evaluate(
            """() => {
                 const host = document.querySelector('wa-toast');
                 return {
                   inTopLayer: host.matches(':popover-open'),
                   insideADialog: !!host.closest('wa-dialog'),
                 };
               }"""
        ) as Map<*, *>
        assertEquals(true, placement["inTopLayer"], "the toast stack must be in the top layer, or a modal covers it")
        assertEquals(false, placement["insideADialog"], "it should reach the top layer as a popover, not by living in a dialog")
        page.close()
    }

    /**
     * Servings is a <wa-number-input>, not <wa-input type="number">: Web Awesome resets the native
     * spin buttons away, so the plain input offered no stepper at all. It is a different element
     * with its own value plumbing, so that x-model still reaches the API is worth asserting rather
     * than assuming.
     */
    @Test
    fun servingsEditedThroughTheNumberInputReachesTheApi() {
        val b = requireBrowser()
        val page = editorPage(b)
        openEditorFor(page)

        val field = page.locator(".edit wa-number-input")
        assertThat(field).hasCount(1)
        field.evaluate(
            """el => { el.value = '12';
                       el.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }"""
        )
        saveAndWait(page)

        assertEquals(12, stored()?.servings, "the number input's value must round-trip through the save")
        page.close()
    }
}
