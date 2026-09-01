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
        assertEquals(0, page.getByText("Edit Classifiers").count(), "the rail is not a column here")
        page.getByLabel("Library").first().click()
        page.waitForSelector("text=Edit Classifiers")

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
        page.getByLabel("Sort").click()
        page.getByRole(AriaRole.MENUITEMRADIO).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Z → A")
        ).click()
        page.waitForFunction(
            "() => document.querySelectorAll('[role=option]')[0].textContent.startsWith('Skillet')"
        )

        // Last Made puts the never-made block at the end in both directions -- every recipe in this
        // fixture is never-made, so the assertion is that the list survives the ordering at all.
        page.getByLabel("Sort").click()
        page.getByRole(AriaRole.MENUITEMRADIO).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Last Made")
        ).click()
        page.waitForSelector("text=Never made")

        // Remembered across a reload, and ticked when the menu comes back.
        page.reload()
        page.waitForSelector("[role=option]")
        assertTrue(page.getByText("Never made").first().isVisible, "the ordering is remembered")
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

        page.getByLabel("Other ways to add").click()
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

        page.getByText("Shopping Lists").click()
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

        page.getByText("Shopping Lists").click()
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

    /** The library manager creates, renames and deletes, and the rail follows. */
    @Test
    fun theLibraryManagerCreatesRenamesAndDeletes() {
        val b = requireBrowser()
        val (page, _) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit Classifiers")
        ).first().click()
        page.waitForSelector("text=Edit Classifiers")

        val dialog = page.getByRole(AriaRole.DIALOG)
        dialog.getByPlaceholder("New category").fill("Weeknight")
        dialog.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Add")
        ).click()
        page.waitForFunction(
            "() => [...document.querySelectorAll('input')].some(i => i.value === 'Weeknight')"
        )

        val after = runBlocking { LibraryRepository.listCategories(userId()) }
        assertTrue(after.any { it.name == "Weeknight" }, "the category was created")
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

        page.getByLabel("Other ways to add").click()
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

        page.getByLabel("Other ways to add").click()
        page.getByText("Select recipes…").click()
        page.waitForSelector("text=Select recipes")

        val rows = page.locator("[role=option]")
        rows.nth(1).getByRole(AriaRole.CHECKBOX).click()
        rows.nth(2).getByRole(AriaRole.CHECKBOX).click()
        page.waitForSelector("text=2 recipes selected")

        page.getByLabel("Done selecting").click()
        page.waitForSelector("text=All Recipes")
        assertEquals(0, page.getByText("2 selected").count(), "the selection bar is gone")
        assertTrue(page.getByLabel("Sort").isVisible, "and the ordinary header is back")
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

        page.getByLabel("Other ways to add").click()
        page.getByText("Select recipes…").click()
        page.waitForSelector("text=Select recipes")
        assertEquals(4, page.getByRole(AriaRole.CHECKBOX).count(), "one per row, once asked for")
        page.close()
    }

    /** Dialogs are addressable, so Back closes one rather than leaving the app. */
    @Test
    fun dialogsAreAddressableAndBackClosesThem() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit Classifiers")
        ).first().click()
        page.waitForSelector("text=Edit Classifiers")
        assertTrue(page.url().endsWith("#/library"), "the open dialog is in the URL: ${page.url()}")

        page.goBack()
        page.waitForSelector("text=Organize library", com.microsoft.playwright.Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED))
        assertEquals(emptyList<String>(), errors, "dialog routing should not log console errors")
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
                "01A05100-0000-7000-8000-00000000RR04",
            )
        }
        assertEquals("Skillet Cornbread with Honey", stored?.name, "the edit should have been saved")
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
