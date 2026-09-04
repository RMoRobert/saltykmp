package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
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
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.recipe.addressRefusal
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
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
 * Browser tests for the React + Fluent UI web app.
 *
 * These carry forward what `EditorUiTest` encoded before the Alpine app was deleted (it is still on
 * `main` if a behaviour needs looking up). What did not carry forward was anything testing Web
 * Awesome itself -- component upgrade, the wa-page navigation toggle, Alpine's `$data` -- because
 * those tested the library rather than Salty. What did carry forward is every rule about what the
 * app *does*: what persists, what an empty field means, how the list is ordered, and which pane is
 * on screen at which width.
 *
 * The one assertion worth calling out is the console check. A React app that throws during render
 * unmounts the subtree and leaves a *blank pane*, which looks identical in a screenshot to a pane
 * that is legitimately empty. Failing on console errors is what tells those two apart.
 */
class ReactUiSmokeTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-react-img"))

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var port = 0

    companion object {
        private var dbReady = false

        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:saltyreact;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }

        private const val COURSE_MAIN = "01A05100-0000-7000-8000-00000000RC01"
        private const val COURSE_BREAD = "01A05100-0000-7000-8000-00000000RC02"
        private const val CATEGORY_BAKING = "01A05100-0000-7000-8000-00000000RK01"
        private const val TAG_QUICK = "01A05100-0000-7000-8000-00000000RT01"
        private const val PIES_ID = "01A05100-0000-7000-8000-00000000RR01"
        private const val CORNBREAD_ID = "01A05100-0000-7000-8000-00000000RR04"
        private const val LIST_ID = "01A05100-0000-7000-8000-00000000RL01"
        private const val FREEFORM_ID = "01A05100-0000-7000-8000-00000000RL02"

        /** A stand-in recipe site for the import test, served over loopback. */
        private val IMPORTABLE_PAGE = """
            <!doctype html><html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe",
             "name":"Imported Pancakes",
             "recipeYield":"12 pancakes",
             "recipeIngredient":["1 cup flour","1 cup milk"],
             "recipeInstructions":[{"@type":"HowToStep","text":"Heat the pan."}]}
            </script></head><body>Pancakes</body></html>
        """.trimIndent()
    }

    private var recipeSite: HttpServer? = null
    private var sitePort = 0

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
            LibraryRepository.upsertCourse(user.id, ServerCourse(COURSE_MAIN, "Main"))
            LibraryRepository.upsertCourse(user.id, ServerCourse(COURSE_BREAD, "Breads"))
            LibraryRepository.upsertCategory(user.id, ServerCategory(CATEGORY_BAKING, "Baking"))
            LibraryRepository.upsertTag(user.id, ServerTag(TAG_QUICK, "Quick"))

            // The recipe the screenshots show, with enough filled in that every band of the read
            // view has something to render: rating, source, meta, times, sections and steps.
            RecipeRepository.upsert(
                user.id,
                ServerRecipe(
                    id = PIES_ID,
                    name = "Australian Mini Meat Pies",
                    lastModifiedDate = "2026-08-20T00:00:00.000Z",
                    source = "Modified from RecipeTin Eats",
                    sourceDetails = "http://www.recipetineats.com/party-pies-mini-beef-pies/",
                    introduction = "Classic Aussie fare - a meat pie with a beef filling that's " +
                        "been braised until tender. Great food for parties that freezes extremely well!",
                    rating = 4,
                    difficulty = 3,
                    yield = "Makes 16",
                    isFavorite = true,
                    courseId = COURSE_MAIN,
                    categoryIds = listOf(CATEGORY_BAKING),
                    tagIds = listOf(TAG_QUICK),
                    preparationTimes = listOf(
                        PreparationTime(id = "p1", type = "Prep", timeString = "30 min"),
                        PreparationTime(id = "p2", type = "Bake", timeString = "25 min"),
                        PreparationTime(id = "p3", type = "Ready In", timeString = "60 min"),
                    ),
                    ingredients = listOf(
                        Ingredient(id = "i1", text = "Filling", isHeading = true),
                        Ingredient(id = "i2", text = "3 tablespoons olive oil"),
                        Ingredient(id = "i3", text = "2 onions, peeled and diced", isMain = true),
                        Ingredient(id = "i4", text = "1/2 cup red wine"),
                    ),
                    directions = listOf(
                        Direction(id = "d1", text = "Brown the beef in batches."),
                        Direction(id = "d2", text = "Assembly", isHeading = true),
                        Direction(id = "d3", text = "Fill the tins and bake 25 minutes."),
                    ),
                ),
            )

            // A couple more so the list column is a list rather than one row.
            listOf(
                "01A05100-0000-7000-8000-00000000RR02" to "Baked White Bean Cakes",
                "01A05100-0000-7000-8000-00000000RR03" to "Banana-Oat Waffles",
                "01A05100-0000-7000-8000-00000000RR04" to "Skillet Cornbread",
            ).forEachIndexed { i, (id, name) ->
                RecipeRepository.upsert(
                    user.id,
                    ServerRecipe(
                        id = id,
                        name = name,
                        lastModifiedDate = "2026-08-0${i + 1}T00:00:00.000Z",
                        rating = if (i == 1) 5 else null,
                        isFavorite = i == 1,
                        courseId = COURSE_BREAD,
                        introduction = "A short line so the row has a subtitle.",
                    ),
                )
            }

            ShoppingListRepository.save(
                user.id,
                ServerShoppingList(
                    id = LIST_ID,
                    name = "Groceries",
                    isFreeform = false,
                    contentsForList = listOf(
                        ShoppingListListContents(id = "s1", text = "Flour", isCompleted = false),
                    ),
                    lastModifiedDate = "2026-08-01T00:00:00.000Z",
                ),
            )
            ShoppingListRepository.save(
                user.id,
                ServerShoppingList(
                    id = FREEFORM_ID,
                    name = "Notes to self",
                    isFreeform = true,
                    contentsForFreeform = "",
                    lastModifiedDate = "2026-08-01T00:00:00.000Z",
                ),
            )
        }

        // The import runs against a loopback address, so this server is given a policy that permits
        // loopback specifically -- everything else still goes through the real one.
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
        server?.stop(0, 0)
        browser?.close()
        playwright?.close()
        browser = null
        playwright = null
    }

    private fun requireBrowser(): Browser {
        val b = browser ?: runCatching {
            playwright = Playwright.create()
            playwright!!.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }.getOrNull()?.also { browser = it }
        org.junit.Assume.assumeTrue("no Playwright browser available; skipping browser tests", b != null)
        return b!!
    }

    /** Signs in through the real form and waits for React to have rendered the list. */
    private fun appPage(b: Browser): Pair<Page, MutableList<String>> {
        val page = b.newPage(Browser.NewPageOptions().setViewportSize(1400, 900))
        val consoleErrors = mutableListOf<String>()
        page.onConsoleMessage { if (it.type() == "error") consoleErrors.add(it.text()) }
        page.onPageError { consoleErrors.add(it) }

        page.navigate("http://localhost:$port/login")
        page.getByLabel("Username").waitFor()
        page.getByLabel("Username").fill("tester")
        page.getByLabel("Password").fill("pw")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Sign in")
        ).first().click()
        page.waitForURL("**/app")
        // The rows arrive only after /api/recipes resolves, so this waits for mount *and* fetch.
        page.waitForSelector("[role=option]")
        return page to consoleErrors
    }

    @Test
    fun theAppMountsAndListsRecipesFromTheApi() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        val rows = page.locator("[role=option]").count()
        assertEquals(4, rows, "every seeded recipe should be listed")
        assertTrue(
            page.getByText("Australian Mini Meat Pies").first().isVisible,
            "the list should show recipe names",
        )
        assertEquals(emptyList<String>(), errors, "the app should mount without console errors")
        page.close()
    }

    @Test
    fun openingARecipeRendersTheReadView() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")

        // A heading is a list row, not a step, so the two directions are numbered 1 and 2 despite
        // the heading sitting between them.
        assertTrue(page.getByText("Fill the tins and bake 25 minutes.").isVisible)
        assertTrue(page.getByText("Filling").first().isVisible, "ingredient sections should render")
        assertTrue(page.getByText("Prep 30 min").isVisible, "the times line should render")
        assertEquals(emptyList<String>(), errors, "opening a recipe should not log console errors")
        page.close()
    }

    /**
     * Scaling is the read view's one piece of real logic: it rewrites leading quantities and leaves
     * everything else, including lines that have no quantity at all, exactly as written.
     */
    @Test
    fun scalingRewritesQuantitiesAndLeavesTheRestAlone() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")
        page.getByLabel("Scale up").click()

        // 3 tablespoons at 1.5x, and 1/2 cup becoming 3/4 rather than 0.75.
        assertTrue(page.getByText("4 1/2").first().isVisible, "3 → 4 1/2 at 1.5×")
        assertTrue(page.getByText("3/4").first().isVisible, "1/2 → 3/4 at 1.5×")
        page.close()
    }

    /**
     * Chef mode is the read view with the panes around it taken away, so what proves it is that the
     * recipe survives the transition: same document, no re-fetch, and a way back out.
     */
    @Test
    fun chefModeHidesTheOtherPanesAndComesBack() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")
        assertTrue(page.locator("[role=option]").count() > 0, "the list is showing to begin with")

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Chef mode")
        ).first().click()
        page.waitForSelector("text=Exit chef mode")

        assertEquals(0, page.locator("[role=option]").count(), "the list pane goes away in chef mode")
        assertTrue(page.getByText("Fill the tins and bake 25 minutes.").isVisible,
            "the recipe is still on screen")

        // Escape is the other way out, and the one a pair of floury hands is likelier to find.
        page.keyboard().press("Escape")
        page.waitForSelector("[role=option]")
        assertEquals(emptyList<String>(), errors, "chef mode should not log console errors")
        page.close()
    }

    /**
     * The guard the browser cannot provide: navigating *inside* the app never reaches beforeunload,
     * so an unsaved edit has to be defended in React or not at all.
     */
    @Test
    fun leavingAnUnsavedEditAsksFirst() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Edit").setExact(true),
        ).first().click()
        page.waitForSelector("text=Edit recipe")
        page.getByLabel("Name").fill("Australian Mini Meat Pies (v2)")

        page.getByText("Skillet Cornbread").first().click()
        page.waitForSelector("text=Discard unsaved changes?")

        // Scoped to the dialog: the editor has a Cancel of its own, sitting behind the backdrop
        // where it can never be clicked -- an unscoped locator finds that one and waits forever.
        val dialog = page.getByRole(AriaRole.DIALOG)

        // Keeping the edit leaves the editor exactly where it was, holding the typed value.
        dialog.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Cancel")
        ).first().click()
        assertEquals("Australian Mini Meat Pies (v2)", page.getByLabel("Name").inputValue())

        page.getByText("Skillet Cornbread").first().click()
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Discard")
        ).first().click()
        page.waitForSelector("text=Chef mode")
        page.close()
    }

    /**
     * About is a collapsed section at the bottom of Settings, which is where Microsoft's own
     * app-settings guidance puts "app information that isn't accessed very often" -- not a
     * top-level entry, and not the account menu.
     */
    @Test
    fun aboutIsTheLastSectionOfSettings() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        assertEquals(0, page.getByText("About Salty").count(), "About is not a top-level entry")

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Settings")
        ).first().click()
        page.waitForSelector("text=Apps and devices")

        // Collapsed to begin with: the version is behind the header, not beside it.
        val dialog = page.getByRole(AriaRole.DIALOG)
        assertEquals(0, dialog.getByText("Signed in as").count(), "About starts collapsed")
        dialog.getByText("About Salty").click()
        page.waitForSelector("text=Signed in as")

        assertEquals(emptyList<String>(), errors, "Settings should render without console errors")
        page.close()
    }


    /* ------------------------------------------------------------------ helpers -- */

    private fun userId() = runBlocking { UserRepository.findByUsername("tester")!!.id }

    private fun storedPies() = runBlocking { RecipeRepository.getById(userId(), PIES_ID) }

    private fun stored(id: String) = runBlocking { RecipeRepository.getById(userId(), id) }

    /**
     * Waits for a write that the UI acknowledges optimistically and reports no toast for -- the
     * Last prepared menu's date, which updates on screen before the PUT has landed. Polling the row is
     * what makes reading it back deterministic rather than a guess at how long the request takes.
     */
    private fun awaitPies(what: String, until: (com.enuvro.saltykmp.api.ServerRecipe?) -> Boolean) =
        awaitRecipe(PIES_ID, what, until)

    private fun awaitRecipe(
        id: String,
        what: String,
        until: (com.enuvro.saltykmp.api.ServerRecipe?) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (until(stored(id))) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * Waits for the address to say `hash`.
     *
     * `waitForURL` is the obvious tool and the wrong one: it waits for a navigation, and a route
     * change here is a pushState in a page that never navigates.
     */
    private fun awaitHash(page: Page, hash: String) =
        page.waitForFunction("h => window.location.hash === h", hash)

    /** Right-clicks a row and waits for its menu. The row is NOT opened by this -- that is the point. */
    private fun openRowMenu(page: Page, name: String) {
        page.locator("[role=option]").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText(name)
        ).first().click(
            com.microsoft.playwright.Locator.ClickOptions()
                .setButton(com.microsoft.playwright.options.MouseButton.RIGHT)
        )
        page.waitForSelector("text=Open in new tab")
    }

    private fun storedCount() = runBlocking {
        RecipeRepository.listForSync(userId(), null, null, 100).recipes.size
    }

    /** Opens a recipe and puts it in the editor, which is three clicks in every test that edits. */
    private fun openEditor(page: Page, name: String) {
        page.getByText(name).first().click()
        page.waitForSelector("text=Chef mode")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Edit").setExact(true),
        ).first().click()
        page.waitForSelector("text=Edit recipe")
    }

    /** Saves, and waits for the toast rather than for a fixed time. */
    private fun save(page: Page) {
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Save")
        ).first().click()
        page.waitForSelector("text=Saved")
        page.waitForTimeout(300.0) // the image call, when there is one, lands just after the toast
    }

    /* --------------------------------------------------- guards and empty panes -- */

    /**
     * A draft exists only in this tab until Save, so leaving it has to take it with you.
     *
     * It used to survive: clicking a library filter set the mode back to "read" but kept the draft
     * as the recipe on show, giving an "Untitled" recipe with working Edit, Delete and favourite
     * buttons -- and favouriting it CREATED it on the server.
     */
    @Test
    fun aBlankDraftDoesNotSurviveALibraryClick() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByLabel("New recipe").first().click()
        page.waitForSelector("text=New recipe")
        page.getByText("All recipes").first().click()

        page.waitForSelector("text=Select a recipe.")
        assertEquals(0, page.getByText("Untitled").count(), "no phantom recipe is left behind")
        assertEquals(emptyList<String>(), errors)
        page.close()
    }

    /**
     * On a phone the detail pane is the whole screen, so an empty one has to carry the way back.
     * Deleting a recipe there used to leave "Select a recipe." with the list and rail unmounted and
     * nothing at all to press.
     */
    @Test
    fun deletingOnAPhoneLeavesAWayBackToTheList() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)
        page.setViewportSize(420, 800)

        page.getByText("Skillet Cornbread").first().click()
        page.waitForSelector("text=Chef mode")
        page.getByLabel("More actions").click()
        page.getByText("Delete recipe…").click()
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Delete")
        ).first().click()

        // Back on the list, which is the pane a phone should be left looking at.
        page.waitForSelector("[role=option]")
        assertEquals(emptyList<String>(), errors)
        page.close()
    }

    /**
     * Everything that replaces what the editor is holding asks first. Importing from the web did
     * not, so an edit in progress vanished the moment the fetch came back.
     */
    @Test
    fun importingWhileEditingAsksBeforeDiscarding() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        openEditor(page, "Skillet Cornbread")
        page.getByLabel("Name").fill("Cornbread, but edited")

        page.getByLabel("List options").click()
        page.getByText("Import from web…").click()
        page.getByLabel("Recipe page address").fill("http://127.0.0.1:$sitePort/recipe")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Import")
        ).first().click()

        page.waitForSelector("text=Discard unsaved changes?")
        assertEquals(emptyList<String>(), errors)
        page.close()
    }

    /* ------------------------------------------------ ported from EditorUiTest -- */

    /**
     * Three columns do not fit on a phone. Below 900px there is one pane at a time, the rail is a
     * drawer, and each pane carries the way back to the one behind it: detail -> list -> library.
     */
    @Test
    fun compactShowsOnePaneAtATimeWithAWayBack() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)
        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")

        page.setViewportSize(420, 800)
        page.waitForSelector("[role=option]", com.microsoft.playwright.Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED))
        assertTrue(page.getByText("Ingredients").first().isVisible, "the recipe is what is on screen")

        // Detail -> list.
        page.getByLabel("Back to the list").click()
        page.waitForSelector("[role=option]")

        // List -> the library, which is a drawer at this width.
        assertEquals(0, page.getByText("Edit classifiers").count(), "the rail is not a column here")
        page.getByLabel("Show library").click()
        page.waitForSelector("text=Edit classifiers")

        assertEquals(emptyList<String>(), errors, "the compact layout should not log console errors")
        page.close()
    }

    /** The library rail filters the list, and expanding a group is not the same as choosing one. */
    @Test
    fun libraryFiltersTheListAndExpandingAGroupDoesNot() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        assertEquals(4, page.locator("[role=option]").count())

        page.getByText("Courses").click()
        page.waitForTimeout(300.0)
        assertEquals(4, page.locator("[role=option]").count(), "expanding a group is not a filter")

        page.getByText("Breads").click()
        page.waitForFunction("() => document.querySelectorAll('[role=option]').length === 3")
        assertEquals(0, page.getByText("Australian Mini Meat Pies").count(), "a Main is not a Bread")
        page.close()
    }

    /** Choosing a filter the open recipe does not belong to clears the pane showing it. */
    @Test
    fun changingTheFilterClearsARecipeThatIsNoLongerListed() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")

        page.getByText("Courses").click()
        page.getByText("Breads").click()
        page.waitForSelector("text=Select a recipe.")
        page.close()
    }

    /**
     * Every ordering, in both directions, and the chosen one remembered. The fixture's four
     * orderings are deliberately all different, so a picker wired to the wrong field cannot pass
     * by coincidence.
     */
    @Test
    fun everySortFieldOrdersTheListAndIsRemembered() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        fun names() = page.locator("[role=option]").allTextContents().map { it.substringBefore("A short").trim() }

        assertTrue(names().first().startsWith("Australian"), "name ascending opens on A")
        page.getByLabel("List options").click()
        page.getByRole(AriaRole.MENUITEMRADIO).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Z → A")
        ).click()
        page.waitForFunction(
            "() => document.querySelectorAll('[role=option]')[0].textContent.startsWith('Skillet')"
        )

        // Last prepared puts the never-prepared block at the end in both directions -- every recipe in this
        // fixture is never-made, so the assertion is that the list survives the ordering at all.
        page.getByLabel("List options").click()
        page.getByRole(AriaRole.MENUITEMRADIO).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Last prepared")
        ).click()
        page.waitForSelector("text=Never prepared")

        // Remembered across a reload, and ticked when the menu comes back.
        page.reload()
        page.waitForSelector("[role=option]")
        assertTrue(page.getByText("Never prepared").first().isVisible, "the ordering is remembered")
        page.close()
    }

    /**
     * The recipe's three dates: shown in Get info, and the one of them that is set from the menu.
     *
     * `lastPrepared` was sorted on and shown on the row but could not be answered from a browser at
     * all; it is answered from the Last prepared menu, which is where the CMP and Swift apps keep it.
     * Get info reports it and no longer sets it, so this walks both halves. Two assertions carry the
     * rule rather than the feature. The stored value is checked for LOCAL NOON, the convention
     * `PreparedDates` and the Swift app write into this column so a picked day renders as that day
     * in every zone. And `lastModifiedDate` is checked to be *unchanged*: marking a recipe made is
     * not a body edit, and bumping it would reorder every client's "Date Modified" sort.
     */
    @Test
    fun getInfoShowsTheDatesAndTheMenuSetsLastMade() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)
        val modifiedBefore = storedPies()?.lastModifiedDate

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Chef mode")
        page.getByLabel("More actions").click()
        page.getByText("Get info").click()

        val panel = page.getByRole(AriaRole.DIALOG)
        panel.getByText("Added").waitFor()
        // Exact: "Last prepared" appears twice in the panel, the second time under Sync details.
        assertTrue(
            panel.getByText(
                "Modified",
                com.microsoft.playwright.Locator.GetByTextOptions().setExact(true),
            ).isVisible,
            "the panel shows when it was last edited",
        )
        assertTrue(panel.getByText("Not set").isVisible, "and says so when there is no date yet")
        assertEquals(
            0,
            panel.getByLabel("Date prepared").count(),
            "Get info reports the date, it does not set it",
        )
        page.getByLabel("Close").click()
        panel.waitFor(
            com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
        )

        page.getByLabel("More actions").click()
        page.getByText("Last prepared").click()
        // The submenu reads the date at its head, so it says "Not set" before there is one.
        assertTrue(
            page.getByText("Not set").isVisible,
            "the menu says where the date stands before offering to change it",
        )
        page.getByText("Set as date…").click()

        // The field caps itself at today: a recipe cannot have been made in the future, which is
        // the same rule the CMP picker states with its selectable-dates object.
        val field = page.getByLabel("Date prepared")
        assertEquals(java.time.LocalDate.now().toString(), field.getAttribute("max"))

        field.fill("2026-08-14")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Save").setExact(true),
        ).click()
        awaitPies("the picked date to be stored") { it?.lastPrepared != null }

        val stored = storedPies()
        val local = java.time.Instant.parse(stored?.lastPrepared)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
        assertEquals(java.time.LocalDate.of(2026, 8, 14), local.toLocalDate(), "the day that was picked")
        assertEquals(12, local.hour, "stored at LOCAL noon, as PreparedDates does")
        assertTrue(stored?.lastModifiedPreparedDate != null, "the date travels with its own stamp")
        assertEquals(modifiedBefore, stored?.lastModifiedDate, "marking a recipe made is not an edit")

        // Get info is where the answer is read back.
        page.getByLabel("More actions").click()
        page.getByText("Get info").click()
        assertTrue(panel.getByText("Aug 14, 2026").isVisible, "Get info reads back the day it stored")
        page.getByLabel("Close").click()
        panel.waitFor(
            com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
        )

        // Clear is offered because one date field overwrites irreversibly, so a mis-pick needs a
        // way back -- the same reason the CMP menu offers it. It empties the field rather than
        // writing through, so nothing is stored until Save, and Cancel would undo it.
        page.getByLabel("More actions").click()
        page.getByText("Last prepared").click()
        assertTrue(
            page.getByText("Aug 14, 2026").isVisible,
            "and the menu reads back the day it stored",
        )
        page.getByText("Set as date…").click()
        assertEquals("2026-08-14", page.getByLabel("Date prepared").inputValue(), "seeded from the stored day")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Clear").setExact(true),
        ).click()
        assertEquals("", page.getByLabel("Date prepared").inputValue(), "Clear empties the field")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Save").setExact(true),
        ).click()
        awaitPies("the date to be cleared") { it?.lastPrepared == null }

        assertEquals(emptyList<String>(), errors, "the menu and the panel render without console errors")
        page.close()
    }

    /** The app opens on nothing selected, and a delete returns there rather than to a neighbour. */
    @Test
    fun theAppOpensOnNoSelectionAndADeleteReturnsToIt() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        assertTrue(page.getByText("Select a recipe.").isVisible, "nothing is selected at startup")

        page.getByText("Skillet Cornbread").first().click()
        page.waitForSelector("text=Chef mode")
        page.getByLabel("More actions").click()
        page.getByText("Delete recipe…").click()
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Delete")
        ).first().click()

        page.waitForSelector("text=Select a recipe.")
        page.waitForFunction("() => document.querySelectorAll('[role=option]').length === 3")
        page.close()
    }

    /**
     * The editor writes back the fields it shows AND leaves alone the ones it does not touch --
     * the failure this guards against is a save that quietly drops ingredients.
     */
    @Test
    fun savingKeepsWhatTheEditorDidNotTouch() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        openEditor(page, "Australian Mini Meat Pies")

        page.getByLabel("Yield").fill("Makes 24")
        save(page)

        val stored = storedPies()
        assertEquals("Makes 24", stored?.yield)
        assertEquals(4, stored?.ingredients?.size, "ingredients survive an edit that ignored them")
        assertEquals(3, stored?.directions?.size, "so do directions")
        assertEquals(3, stored?.preparationTimes?.size, "and so do the times")
        assertEquals(4, stored?.rating, "and the rating")
        page.close()
    }

    /** Difficulty offers every level the shared enum defines, and survives a reopen. */
    @Test
    fun difficultyOffersEveryLevelAndSurvivesAReopen() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        openEditor(page, "Australian Mini Meat Pies")

        page.getByLabel("Difficulty").click()
        // Asserting the labels rather than counting roles: the recipe list is a listbox as well, so
        // a page-wide count of options adds the two together.
        for (level in listOf("Easy", "Somewhat Easy", "Medium", "Slightly Difficult", "Difficult")) {
            assertTrue(
                page.getByRole(AriaRole.OPTION).filter(
                    com.microsoft.playwright.Locator.FilterOptions().setHasText(level)
                ).count() > 0,
                "the difficulty menu offers $level",
            )
        }
        page.getByRole(AriaRole.OPTION).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Difficult").setHasNotText("Slightly")
        ).first().click()
        save(page)

        assertEquals(5, storedPies()?.difficulty)
        page.close()
    }

    /** Notes, variations and preparation times all round-trip through the editor. */
    @Test
    fun notesVariationsAndTimesPersist() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        openEditor(page, "Australian Mini Meat Pies")

        page.getByText("Notes (0)").click()
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Add note")
        ).click()
        page.getByPlaceholder("Title").fill("Freezing")
        save(page)

        val stored = storedPies()
        assertEquals(1, stored?.notes?.size)
        assertEquals("Freezing", stored?.notes?.first()?.title)
        page.close()
    }

    /**
     * A zero is a measurement; an empty field is not. And a record emptied of every field is no
     * record at all -- otherwise a recipe that once had nutrition could never go back to having
     * none, and the reading view would keep a heading over an empty table.
     */
    @Test
    fun nutritionKeepsZeroAndAnEmptiedRecordIsRemoved() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        openEditor(page, "Australian Mini Meat Pies")

        page.getByText("Nutrition").click()
        page.getByLabel("Calories").fill("0")
        save(page)
        assertEquals(0.0, storedPies()?.nutrition?.calories as Double?, "a deliberate zero is kept")

        openEditor(page, "Australian Mini Meat Pies")
        page.getByText("Nutrition").click()
        page.getByLabel("Calories").fill("")
        save(page)
        assertEquals(null, storedPies()?.nutrition, "an emptied record is not stored")
        page.close()
    }

    /** A tag can be made without leaving the editor, and lands on the recipe being edited. */
    @Test
    fun aTagCanBeCreatedFromInsideTheEditor() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        openEditor(page, "Australian Mini Meat Pies")

        page.getByLabel("New tag").click()
        page.getByRole(AriaRole.DIALOG).getByLabel("Tag name").fill("Sheet Pan")
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Create")
        ).click()
        page.waitForTimeout(600.0)
        save(page)

        val tags = runBlocking { LibraryRepository.listTags(userId()) }
        assertTrue(tags.any { it.name == "Sheet Pan" }, "the tag was created")
        assertEquals(2, storedPies()?.tagIds?.size, "and applied to the recipe being edited")
        page.close()
    }

    /** An imported draft opens for review and is not in the library until it is saved. */
    @Test
    fun anImportedDraftIsNotSavedUntilItIsSaved() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByLabel("List options").click()
        page.getByText("Import from web…").click()
        page.waitForSelector("text=Recipe page address")
        page.getByLabel("Recipe page address").fill("http://127.0.0.1:$sitePort/recipe")
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Import")
        ).click()

        page.waitForFunction(
            "() => document.querySelector('input')?.value === 'Imported Pancakes' || " +
                "[...document.querySelectorAll('input')].some(i => i.value === 'Imported Pancakes')"
        )
        assertEquals("Imported Pancakes", page.getByLabel("Name").inputValue())
        assertEquals(4, storedCount(), "nothing is saved by importing")

        save(page)
        assertEquals(5, storedCount(), "saving the draft adds it")
        page.close()
    }

    /** Enter adds a shopping-list item, and the list saves itself without a save button. */
    @Test
    fun pressingEnterAddsAShoppingListItemAndSaves() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("All lists").click()
        page.waitForSelector("text=Groceries")
        page.getByText("Groceries").first().click()
        page.getByPlaceholder("Add an item").waitFor()

        page.getByPlaceholder("Add an item").fill("Butter")
        page.keyboard().press("Enter")
        page.waitForTimeout(900.0)

        val stored = runBlocking { ShoppingListRepository.getById(userId(), LIST_ID) }
        assertTrue(
            stored?.contentsForList?.any { it.text == "Butter" } == true,
            "the item reached the server without a save button",
        )
        page.close()
    }

    /** A markdown list saves its text and leaves the checklist column alone. */
    @Test
    fun aMarkdownListSavesItsTextAndLeavesTheChecklistColumnNull() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("All lists").click()
        page.waitForSelector("text=Notes to self")
        page.getByText("Notes to self").first().click()
        page.locator("textarea").waitFor()

        page.locator("textarea").fill("- milk\n- eggs")
        page.locator("textarea").blur()
        page.waitForTimeout(900.0)

        val stored = runBlocking { ShoppingListRepository.getById(userId(), FREEFORM_ID) }
        assertEquals("- milk\n- eggs", stored?.contentsForFreeform)
        assertEquals(null, stored?.contentsForList, "the checklist column stays null")
        page.close()
    }

    /**
     * Deleting the list you are reading lands on the empty state. The pane used to keep the
     * deleted id and sit on its spinner, waiting for a list that no longer existed.
     */
    @Test
    fun deletingTheOpenListLandsOnTheEmptyState() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByText("All lists").click()
        page.waitForSelector("text=Groceries")
        page.getByText("Groceries").first().click()
        page.getByPlaceholder("Add an item").waitFor()

        page.getByLabel("List actions").click()
        page.getByText("Delete list…").click()
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Delete")
        ).click()

        page.getByText("Select a shopping list.").waitFor()
        assertEquals(0, page.getByText("Loading…").count(), "no spinner waits for the deleted list")
        assertEquals(0, page.getByText("Groceries").count(), "and the rail no longer lists it")
        assertEquals(null, runBlocking { ShoppingListRepository.getById(userId(), LIST_ID) })
        assertEquals(emptyList<String>(), errors)
        page.close()
    }

    /** The library manager creates, renames and deletes, and the rail follows. */
    @Test
    fun theLibraryManagerCreatesRenamesAndDeletes() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit classifiers")
        ).first().click()
        page.waitForSelector("text=Edit classifiers")

        val dialog = page.getByRole(AriaRole.DIALOG)
        dialog.getByPlaceholder("New category").fill("Weeknight")
        dialog.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Add")
        ).click()
        // Wait for BOTH: the draft box empty, and a row carrying the name. The draft box is what
        // says the create actually landed -- it is cleared only once the request succeeds, so that a
        // failed one leaves the typed name to retry rather than a toast and an empty field. Waiting
        // on the name alone matched the draft box itself and let the assertion below race the write.
        page.waitForFunction(
            """
            () => {
              const inputs = [...document.querySelectorAll('input')];
              const draft = inputs.find(i => i.placeholder === 'New category');
              return draft && draft.value === '' && inputs.some(i => i.value === 'Weeknight');
            }
            """.trimIndent()
        )

        val after = runBlocking { LibraryRepository.listCategories(userId()) }
        assertTrue(after.any { it.name == "Weeknight" }, "the category was created")
        page.close()
    }

    /**
     * Select mode folds two courses into one, with the survivor chosen in the dialog.
     *
     * "Breads" has three recipes and "Main" one, so Breads is what the dialog pre-picks; the test
     * picks Main instead, which is what proves the choice is honoured rather than the ranking. The
     * database is then checked for the two halves of the sync contract: every recipe re-pointed,
     * and only the re-pointed ones restamped.
     */
    @Test
    fun theLibraryManagerMergesWithTheSurvivorChosen() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit classifiers")
        ).first().click()
        page.waitForSelector("text=Edit classifiers")
        val manager = page.getByRole(AriaRole.DIALOG).first()
        manager.getByRole(AriaRole.TAB, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Courses")).click()
        manager.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Select").setExact(true)).click()

        // Two rows checked: the bar counts them, and Merge wakes up.
        val merge = manager.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Merge…"))
        assertTrue(merge.isDisabled, "one row is nothing to merge into")
        manager.getByRole(AriaRole.CHECKBOX, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Breads")).check()
        manager.getByRole(AriaRole.CHECKBOX, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Main")).check()
        page.waitForSelector("text=2 selected")
        merge.click()

        page.waitForSelector("text=Merge 2 courses")
        val confirm = page.getByRole(AriaRole.DIALOG).last()
        // Breads (3 recipes) is pre-picked, and the message is written in its name.
        assertTrue(confirm.getByRole(AriaRole.RADIO, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Breads")).isChecked)
        page.waitForSelector("text=\"Main\" will be deleted, and its recipes will use \"Breads\" instead")
        // Choose the other one; the message follows.
        confirm.getByRole(AriaRole.RADIO, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Main")).check()
        page.waitForSelector("text=\"Breads\" will be deleted, and its recipes will use \"Main\" instead")
        confirm.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Merge").setExact(true)).click()

        // The toast says what happened, and the rail no longer lists the course that went.
        page.waitForSelector("text=Merged 1 course into Main")
        page.waitForSelector(
            "text=Breads",
            com.microsoft.playwright.Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
        )

        val courses = runBlocking { LibraryRepository.listCourses(userId()) }
        assertEquals(listOf("Main"), courses.map { it.name })
        val recipes = runBlocking { RecipeRepository.listForSync(userId(), null, null, 100).recipes }
        assertTrue(recipes.all { it.courseId == COURSE_MAIN }, "every recipe now uses the survivor: ${recipes.map { it.name to it.courseId }}")
        val cornbread = runBlocking { RecipeRepository.getById(userId(), CORNBREAD_ID)!! }
        assertTrue(cornbread.lastModifiedDate!! > "2026-08-03T00:00:00.000Z", "a re-pointed recipe is restamped, so the native clients download it")
        assertEquals("2026-08-20T00:00:00.000Z", storedPies()!!.lastModifiedDate, "the recipe that already had the survivor is untouched")
        assertEquals(emptyList<String>(), errors, "merging should not log console errors")
        page.close()
    }

    /**
     * Select mode deletes several rows at once, after a confirmation that counts what they cost.
     * Both seeded courses go, which also proves the last row of a kind can be deleted from here,
     * and every recipe that had a course is restamped for the sync.
     */
    @Test
    fun theLibraryManagerDeletesSeveralAtOnce() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit classifiers")
        ).first().click()
        page.waitForSelector("text=Edit classifiers")
        val manager = page.getByRole(AriaRole.DIALOG).first()
        manager.getByRole(AriaRole.TAB, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Courses")).click()
        manager.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Select").setExact(true)).click()

        val delete = manager.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Delete").setExact(true))
        assertTrue(delete.isDisabled, "nothing checked, nothing to delete")
        manager.getByRole(AriaRole.CHECKBOX, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Breads")).check()
        manager.getByRole(AriaRole.CHECKBOX, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Main")).check()
        page.waitForSelector("text=2 selected")
        delete.click()

        // The confirmation counts the recipes: three under Breads and one under Main.
        page.waitForSelector("text=Delete 2 courses?")
        page.waitForSelector("text=4 recipes are currently classified with these courses.")
        page.getByRole(AriaRole.DIALOG).last()
            .getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Delete").setExact(true)).click()

        page.waitForSelector("text=Deleted 2 courses")
        page.waitForSelector(
            "text=Breads",
            com.microsoft.playwright.Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
        )

        assertEquals(0, runBlocking { LibraryRepository.countCourses(userId()) })
        val recipes = runBlocking { RecipeRepository.listForSync(userId(), null, null, 100).recipes }
        assertTrue(recipes.all { it.courseId == null }, "no recipe still names a deleted course: ${recipes.map { it.name to it.courseId }}")
        assertTrue(storedPies()!!.lastModifiedDate!! > "2026-08-20T00:00:00.000Z", "a recipe that lost its course is restamped for the sync")
        assertEquals(emptyList<String>(), errors, "deleting should not log console errors")
        page.close()
    }

    /** An empty classifier group offers nothing to select. */
    @Test
    fun anEmptyLibraryGroupIsNotSelectable() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        page.getByText("Tags").click()
        page.waitForSelector("text=Quick")
        // The seeded tag is there; a group with nothing in it says so rather than offering a filter.
        assertTrue(page.getByText("Quick").isVisible)
        page.close()
    }

    /** The columns scroll, the window does not. */
    @Test
    fun theColumnsScrollInsteadOfTheWindow() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        val overflow = page.evaluate("() => getComputedStyle(document.body).overflow")
        assertEquals("hidden", overflow, "the page itself must not scroll")
        page.close()
    }

    /** The divider resizes the list column, and the width is remembered. */
    @Test
    fun theListDividerResizesAndIsRemembered() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        val before = page.locator("[role=listbox]").boundingBox().width
        val gutter = page.getByLabel("Resize recipe list")
        val box = gutter.boundingBox()
        page.mouse().move(box.x + 4, box.y + 200)
        page.mouse().down()
        page.mouse().move(box.x + 120, box.y + 200)
        page.mouse().up()
        page.waitForTimeout(400.0)

        val after = page.locator("[role=listbox]").boundingBox().width
        assertTrue(after > before + 50, "the list column grew: $before -> $after")

        page.reload()
        page.waitForSelector("[role=option]")
        val restored = page.locator("[role=listbox]").boundingBox().width
        assertTrue(kotlin.math.abs(restored - after) < 12, "the width is remembered: $after vs $restored")
        page.close()
    }

    /** A long name is clipped, not allowed to widen or scroll its column. */
    @Test
    fun aLongRecipeNameIsClipped() {
        val b = requireBrowser()
        val (page, _) = appPage(b)
        val scrollWidth = page.evaluate(
            "() => { const l = document.querySelector('[role=listbox]'); return l.scrollWidth - l.clientWidth; }"
        )
        assertEquals(0, scrollWidth, "the list column does not scroll sideways")
        page.close()
    }

    /**
     * The checkbox selects for a bulk action; the row itself still opens. Fluent's ListItem fires
     * onAction before it toggles and skips the toggle when the handler prevents default, which is
     * what lets both live on one row without a mode switch.
     */
    @Test
    fun checkingSeveralRecipesDeletesThemTogether() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByLabel("List options").click()
        page.getByText("Select recipes…").click()
        page.waitForSelector("text=Select recipes")

        val rows = page.locator("[role=option]")
        rows.nth(1).getByRole(AriaRole.CHECKBOX).click()
        rows.nth(2).getByRole(AriaRole.CHECKBOX).click()

        // Past one there is nothing single to show, so the detail pane steps aside.
        page.waitForSelector("text=2 recipes selected")
        assertTrue(page.getByText("2 selected").isVisible, "the list header counts the selection")

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Delete")
        ).first().click()
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Delete")
        ).first().click()

        page.waitForFunction("() => document.querySelectorAll('[role=option]').length === 2")
        assertEquals(2, storedCount(), "both were deleted on the server, not just in the list")
        assertEquals(emptyList<String>(), errors, "bulk delete should not log console errors")
        page.close()
    }

    /** Clearing the selection puts the list header, and the detail pane, back as they were. */
    @Test
    fun clearingTheSelectionRestoresTheHeader() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByLabel("List options").click()
        page.getByText("Select recipes…").click()
        page.waitForSelector("text=Select recipes")

        val rows = page.locator("[role=option]")
        rows.nth(1).getByRole(AriaRole.CHECKBOX).click()
        rows.nth(2).getByRole(AriaRole.CHECKBOX).click()
        page.waitForSelector("text=2 recipes selected")

        page.getByLabel("Done selecting").click()
        page.waitForSelector("text=All recipes")
        assertEquals(0, page.getByText("2 selected").count(), "the selection bar is gone")
        assertTrue(page.getByLabel("List options").isVisible, "and the ordinary header is back")
        assertEquals(0, page.getByRole(AriaRole.CHECKBOX).count(), "and the checkboxes with it")
        page.close()
    }

    /** Outside select mode there are no checkboxes at all, and a click reads a recipe. */
    @Test
    fun thereAreNoCheckboxesUntilSelectModeIsEntered() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        assertEquals(0, page.getByRole(AriaRole.CHECKBOX).count(), "no checkbox column by default")
        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")

        page.getByLabel("List options").click()
        page.getByText("Select recipes…").click()
        page.waitForSelector("text=Select recipes")
        assertEquals(4, page.getByRole(AriaRole.CHECKBOX).count(), "one per row, once asked for")
        page.close()
    }

    /**
     * A favourite is marked in the list and a non-favourite carries nothing at all -- no outline,
     * no empty slot, and in neither case anything clickable. Changing it is the recipe's own menu.
     */
    @Test
    fun theListMarksFavouritesAndOnlyFavourites() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        val favourite = page.locator("[role=option]").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Australian Mini Meat Pies")
        )
        val plain = page.locator("[role=option]").filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Skillet Cornbread")
        )

        assertEquals(1, favourite.getByLabel("Favorite").count(), "the favourite is marked")
        assertEquals(0, plain.getByLabel("Favorite").count(), "the non-favourite is not")
        assertEquals(
            0, favourite.getByRole(AriaRole.BUTTON).count(),
            "and the mark is not a button",
        )
        page.close()
    }

    /** Dialogs are addressable, so Back closes one rather than leaving the app. */
    @Test
    fun dialogsAreAddressableAndBackClosesThem() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit classifiers")
        ).first().click()
        page.waitForSelector("text=Edit classifiers")
        assertTrue(page.url().endsWith("#/library"), "the open dialog is in the URL: ${page.url()}")

        page.goBack()
        page.waitForSelector("text=Organize library", com.microsoft.playwright.Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED))
        assertEquals(emptyList<String>(), errors, "dialog routing should not log console errors")
        page.close()
    }

    /* ---------------------------------------------------- addressable recipes -- */

    /**
     * A recipe is addressable, and Back walks back through the ones that were opened.
     *
     * The address is what opens a recipe -- a click sets it and the route effect does the rest -- so
     * this is also what proves that a click and a press of Back are the same code path.
     */
    @Test
    fun aRecipeIsAddressableAndBackReturnsToTheOneBefore() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")
        assertTrue(page.url().endsWith("#/recipe/$PIES_ID"), "the open recipe is the address: ${page.url()}")

        page.getByText("Skillet Cornbread").first().click()
        awaitHash(page, "#/recipe/$CORNBREAD_ID")

        page.goBack()
        awaitHash(page, "#/recipe/$PIES_ID")
        // The address arrives first and the recipe follows it -- Back is a route change like any
        // other, so what proves it worked is the fetch it starts landing in the pane.
        page.waitForSelector("text=Fill the tins and bake 25 minutes.")
        assertEquals(emptyList<String>(), errors, "recipe routing should not log console errors")
        page.close()
    }

    /**
     * The address works in a tab that has never seen the list -- which is the whole point of having
     * one -- and `/edit` lands in the editor rather than the read view.
     */
    @Test
    fun aPastedRecipeAddressOpensThatRecipe() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.navigate("http://localhost:$port/app#/recipe/$PIES_ID")
        page.waitForSelector("text=Ingredients")
        assertTrue(page.getByText("Fill the tins and bake 25 minutes.").isVisible)

        page.navigate("http://localhost:$port/app#/recipe/$PIES_ID/edit")
        page.waitForSelector("text=Edit recipe")
        assertEquals(
            "Australian Mini Meat Pies", page.getByLabel("Name").inputValue(),
            "the editor opened on the addressed recipe",
        )
        assertEquals(emptyList<String>(), errors, "a pasted address should not log console errors")
        page.close()
    }

    /**
     * A bookmark outlives the recipe it names. Landing on one that is gone says so and falls back to
     * the list, rather than sitting on an empty pane under an address that will never resolve.
     */
    @Test
    fun anAddressForAMissingRecipeFallsBackToTheList() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.navigate("http://localhost:$port/app#/recipe/01A05100-0000-7000-8000-00000000RR99")
        // The address giving up is the assertion: the placeholder below is on screen from the moment
        // the app mounts, so waiting for it would prove nothing about the load that failed.
        awaitHash(page, "")
        assertTrue(page.getByText("Select a recipe.").isVisible, "the pane says there is nothing open")
        assertEquals(
            0, page.locator("[role=option][aria-selected=true]").count(),
            "and nothing is left selected in the list",
        )
        page.close()
    }

    /* --------------------------------------------------------- the row's menu -- */

    /**
     * The row menu's whole reason for existing: it acts on a recipe WITHOUT opening it, which is
     * what the desktop app's right-click does and what the detail pane's own menu cannot do.
     *
     * Every item works off the list row, which is a summary -- the favourite flag this toggles and
     * the date the submenu prints are both on it, so the menu needs no fetch of its own.
     */
    @Test
    fun theRowMenuActsOnARowWithoutOpeningIt() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        openRowMenu(page, "Skillet Cornbread")
        page.getByText("Add to favorites").click()

        awaitRecipe(CORNBREAD_ID, "the favourite to be written") { it?.isFavorite == true }
        assertTrue(
            page.getByText("Select a recipe.").isVisible,
            "the recipe was never opened -- the detail pane is still empty",
        )
        assertTrue(page.url().endsWith("/app"), "and the address did not move: ${page.url()}")
        assertEquals(emptyList<String>(), errors, "the row menu should not log console errors")
        page.close()
    }

    /** Last prepared, set from the row rather than from the open recipe. */
    @Test
    fun theRowMenuSetsTheLastPreparedDate() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        openRowMenu(page, "Australian Mini Meat Pies")
        page.getByText("Last prepared date").click()
        page.getByText("Set to today").click()

        awaitPies("the last prepared date to be written") { it?.lastPrepared != null }
        assertTrue(page.getByText("Select a recipe.").isVisible, "still nothing open")
        page.close()
    }

    /**
     * Delete from a row's menu takes that row, and only that row.
     *
     * The pane used to be emptied whatever was deleted, because the only way to reach Delete was
     * from the recipe that was open. Reached from a row, that closed the recipe the reader was
     * reading because they right-clicked a different one.
     */
    @Test
    fun deletingFromARowMenuLeavesTheOpenRecipeAlone() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")

        openRowMenu(page, "Banana-Oat Waffles")
        page.getByText("Delete recipe…").click()
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Delete").setExact(true),
        ).first().click()
        page.waitForSelector("text=Recipe deleted")

        assertTrue(
            page.getByText("Fill the tins and bake 25 minutes.").isVisible,
            "the recipe that was open is still open",
        )
        assertTrue(page.url().endsWith("#/recipe/$PIES_ID"), "and still addressed: ${page.url()}")
        assertEquals(3, page.locator("[role=option]").count(), "the deleted row is gone")
        page.close()
    }

    /** Edit from a row's menu opens THAT row in the editor, address and all. */
    @Test
    fun editFromTheRowMenuOpensThatRowsEditor() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        openRowMenu(page, "Skillet Cornbread")
        page.getByText("Edit", com.microsoft.playwright.Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector("text=Edit recipe")

        assertEquals("Skillet Cornbread", page.getByLabel("Name").inputValue())
        assertTrue(page.url().endsWith("#/recipe/$CORNBREAD_ID/edit"), "the editor has an address: ${page.url()}")
        page.close()
    }

    /** Editing a recipe writes through to the API, not just to the pane. */
    @Test
    fun savingAnEditReachesTheServer() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByText("Skillet Cornbread").first().click()
        page.waitForSelector("text=Chef mode")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Edit").setExact(true),
        ).first().click()
        page.waitForSelector("text=Edit recipe")
        page.getByLabel("Name").fill("Skillet Cornbread with Honey")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Save")
        ).first().click()
        page.waitForSelector("text=Saved")

        val stored = runBlocking {
            RecipeRepository.getById(
                UserRepository.findByUsername("tester")!!.id,
                CORNBREAD_ID,
            )
        }
        assertEquals("Skillet Cornbread with Honey", stored?.name, "the edit should have been saved")
        page.close()
    }

    /**
     * The list's density is a preference: three styles, chosen in Settings, applied at once and
     * remembered. Summary stays the default, so an existing reader's list does not change under
     * them; each step down is genuinely shorter, which is the whole reason to offer the choice.
     */
    @Test
    fun theRecipeListStyleIsChosenInSettingsAndIsRemembered() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        fun rowHeight() = page.locator("[role=option]").first().boundingBox().height
        fun subtitles() = page.getByText("A short line so the row has a subtitle.").count()

        val summary = rowHeight()
        assertTrue(subtitles() > 0, "Summary is the default, and it carries the second line")

        fun choose(style: String) {
            page.getByRole(AriaRole.BUTTON).filter(
                com.microsoft.playwright.Locator.FilterOptions().setHasText("Settings")
            ).first().click()
            page.waitForSelector("text=Recipe list")
            page.getByRole(
                AriaRole.RADIO,
                com.microsoft.playwright.Page.GetByRoleOptions().setName(style),
            ).click()
            page.keyboard().press("Escape")
            page.waitForSelector(
                "[role=dialog]",
                com.microsoft.playwright.Page.WaitForSelectorOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
            )
        }

        choose("Small icons")
        val small = rowHeight()
        assertTrue(small < summary, "Small icons is shorter than Summary: $summary -> $small")
        assertTrue(subtitles() > 0, "and it keeps the line about the recipe")

        choose("List")
        val list = rowHeight()
        assertTrue(list < small, "List is shorter again: $small -> $list")
        assertEquals(0, subtitles(), "List is one line, so the subtitle goes")

        // A style is a fact about this browser, like the sort and the column width beside it.
        page.reload()
        page.waitForSelector("[role=option]")
        assertTrue(
            kotlin.math.abs(rowHeight() - list) < 2,
            "the style is remembered across a reload: $list vs ${rowHeight()}",
        )
        assertEquals(emptyList<String>(), errors, "changing the style logs no console errors")
        page.close()
    }

    /** An unknown stored style is an old build's, not a bug: it falls back rather than breaking. */
    @Test
    fun anUnknownStoredListStyleFallsBackToSummary() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.evaluate("() => localStorage.setItem('salty.recipeListStyle', 'tiles')")
        page.reload()
        page.waitForSelector("[role=option]")

        assertTrue(
            page.getByText("A short line so the row has a subtitle.").count() > 0,
            "an unrecognised style renders as Summary",
        )
        assertEquals(
            "summary",
            page.evaluate("() => localStorage.getItem('salty.recipeListStyle')"),
            "and the stored value is corrected on the way through",
        )
        page.close()
    }

    /** More rows than fit, so the list column has something to scroll. */
    private fun seedFiller(n: Int) = runBlocking {
        val userId = UserRepository.findByUsername("tester")!!.id
        repeat(n) { i ->
            RecipeRepository.upsert(
                userId,
                ServerRecipe(
                    id = "01A05100-0000-7000-8000-0000000%05d".format(i),
                    name = "Filler Recipe %02d".format(i),
                    lastModifiedDate = "2026-07-01T00:00:00.000Z",
                    introduction = "A short line so the row has a subtitle.",
                ),
            )
        }
    }

    /**
     * The list column scrolls -- as a container, and under a real finger.
     *
     * This is not the same claim as `theColumnsScrollInsteadOfTheWindow`, which only says the window
     * does not scroll, and which passed throughout the whole time the list did not scroll either.
     * The failure it missed was `scrollHeight === clientHeight`: the grid's `height: 100%` had no
     * definite parent, so it grew to its content and the scroller was exactly as tall as its rows,
     * with everything past the fold clipped by body's `overflow: hidden`. Nothing about that was
     * width-dependent, so this asserts it at both.
     */
    @Test
    fun theRecipeListScrollsAtEveryWidthAndUnderAFinger() {
        val b = requireBrowser()
        seedFiller(30)
        val page = b.newPage(
            Browser.NewPageOptions().setViewportSize(390, 844).setHasTouch(true).setIsMobile(true)
        )
        page.navigate("http://localhost:$port/login")
        page.getByLabel("Username").fill("tester")
        page.getByLabel("Password").fill("pw")
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Sign in")
        ).first().click()
        page.waitForURL("**/app")
        page.waitForSelector("[role=option]")

        // The scroller is the list's own container, not the window: `[role=listbox]`'s parent.
        fun metric(prop: String) = page.evaluate(
            "() => document.querySelector('[role=listbox]').parentElement.$prop"
        ) as Int

        for ((label, w) in listOf("phone" to 390, "desktop" to 1400)) {
            page.setViewportSize(w, 844)
            page.waitForTimeout(300.0)
            val client = metric("clientHeight")
            val scroll = metric("scrollHeight")
            assertTrue(
                scroll > client,
                "$label: the list column must have more content than height ($scroll vs $client)",
            )
            assertTrue(client < 844, "$label: and it must fit the window, not exceed it ($client)")
        }

        // A finger, not a wheel: the reported bug was touch, and a stray `touch-action` would pass
        // every assertion above while still leaving the list immovable on a phone.
        page.setViewportSize(390, 844)
        page.waitForTimeout(300.0)
        val cdp = page.context().newCDPSession(page)
        val gesture = com.google.gson.JsonObject().apply {
            addProperty("x", 190)
            addProperty("y", 500)
            addProperty("xDistance", 0)
            addProperty("yDistance", -300)
            addProperty("gestureSourceType", "touch")
        }
        cdp.send("Input.synthesizeScrollGesture", gesture)
        page.waitForTimeout(600.0)
        assertTrue(metric("scrollTop") > 100, "a touch drag scrolls the list: ${metric("scrollTop")}")
        page.close()
    }

    @Test
    fun theEditorOpensWithTheRecipeLoaded() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByText("Australian Mini Meat Pies").first().click()
        page.waitForSelector("text=Ingredients")
        page.getByRole(
            AriaRole.BUTTON,
            com.microsoft.playwright.Page.GetByRoleOptions().setName("Edit").setExact(true),
        ).first().click()

        page.waitForSelector("text=Edit recipe")
        assertEquals(
            "Australian Mini Meat Pies",
            page.getByLabel("Name").inputValue(),
            "the editor should open on the recipe that was being read",
        )
        assertEquals(emptyList<String>(), errors, "the editor should render without console errors")
        page.close()
    }
}
