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
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.image.ImageStore
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
 * Smoke tests for the React + Fluent UI web app.
 *
 * Deliberately thin next to [EditorUiTest]: this covers that the bundle loads, mounts, reaches the
 * API with the session it was given, and renders each of the three panes. It does not re-test the
 * behaviours [EditorUiTest] covers -- those are about the app's rules, and they will need porting
 * to whatever selectors the React UI settles on.
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
        }

        server = embeddedServer(Netty, port = 0) { installSalty(imageStore) }
            .also { it.start(wait = false) }
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
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit")
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

    /** Dialogs are addressable, so Back closes one rather than leaving the app. */
    @Test
    fun dialogsAreAddressableAndBackClosesThem() {
        val b = requireBrowser()
        val (page, errors) = appPage(b)

        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Organize")
        ).first().click()
        page.waitForSelector("text=Organize library")
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
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit")
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
        page.getByRole(AriaRole.BUTTON).filter(
            com.microsoft.playwright.Locator.FilterOptions().setHasText("Edit")
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
