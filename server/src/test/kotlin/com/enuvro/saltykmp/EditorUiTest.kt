package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
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
        const val LIST_ID = "01A05100-0000-7000-8000-0000000000L1"
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
        assertThat(page.locator("section[aria-label='Recipes'] .list__title")).hasText("Baking")
        assertThat(page.locator(".rrow")).hasCount(1)

        // Favourites: nothing is flagged in the fixture, so the list should empty out.
        page.locator("wa-tree-item[data-kind=favorites]").click()
        assertThat(page.locator("section[aria-label='Recipes'] .list__title")).hasText("Favorites")
        assertThat(page.locator(".rrow")).hasCount(0)

        // Back to everything.
        page.locator("wa-tree-item[data-kind=all]").click()
        assertThat(page.locator(".rrow")).hasCount(1)
        page.close()
    }

    /**
     * Assigning a category from the editor must reach the API. The recipe list already returns
     * categoryIds, so this also feeds the sidebar counts.
     */
    @Test
    fun assigningACategoryPersists() {
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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

    /**
     * Editing is modal, as it is in the Swift client. Before this, the rail stayed live behind an
     * open editor, so picking another category swapped the recipe list out from under the recipe
     * being edited -- and each new way out of an edit had to remember to ask about unsaved work.
     */
    @Test
    fun editingIsModalSoTheLibraryCannotBeReachedBehindIt() {
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
        val page = editorPage(b)
        page.locator(".rrow").first().click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")

        page.locator("wa-select[label=Difficulty]").evaluate(
            """el => { el.value = '4';
                       el.dispatchEvent(new Event('change', { bubbles: true, composed: true })); }"""
        )
        page.locator("[data-testid=save]").click()
        page.waitForFunction("() => !Alpine.\$data(document.getElementById('app')).dirty")

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, RECIPE_ID)
        }
        assertEquals(4, stored?.difficulty, "the chosen difficulty should reach the API")

        // Reopen: the level must be visible, not silently dropped on the way back in.
        page.locator("[data-testid=done]").click()
        page.waitForSelector(".read")
        page.locator("[data-testid=edit]").click()
        page.waitForSelector(".edit")
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
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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
        val b = browserOrNull() ?: return
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
     * The reason this pane resolves conflicts at all. A shopping list is worked by several people
     * at once, so a check-off landing between this browser's load and its save must not be undone
     * by it -- and the browser's own edit must not be lost either. Both survive because the save
     * falls back to the shared merge on 409 instead of overwriting.
     */
    @Test
    fun aConcurrentCheckOffIsMergedRatherThanOverwritten() {
        val b = browserOrNull() ?: return
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
}
