package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.db.ShoppingListRepository
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.auth.AccountLockout
import com.enuvro.saltykmp.auth.CSRF_HEADER
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.web.CreateUserRequest
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaltyServerTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-test-img"))

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:salty;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }
    }

    @BeforeTest
    fun reset() {
        ensureDb()
        runBlocking {
            DatabaseFactory.dbQuery {
                RecipeTags.deleteAll(); RecipeCategories.deleteAll()
                Recipes.deleteAll(); Courses.deleteAll(); Categories.deleteAll(); Tags.deleteAll()
                ShoppingLists.deleteAll()
                DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            UserRepository.create("tester", "pw")
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
    }

    /** A browser: session cookie plus JSON, which is what the app itself is. */
    private fun ApplicationTestBuilder.jsonCookieClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
        install(HttpCookies)
        followRedirects = false
    }

    private suspend fun login(client: io.ktor.client.HttpClient): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw", deviceId = "test-device"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<AuthResponse>().deviceToken!!
    }

    private fun recipe(id: String, name: String, lastModified: String) = ServerRecipe(
        id = id, name = name, lastModifiedDate = lastModified,
    )

    // ---- Web UI ----

    @Test
    fun webLoginPageRenders() = testApplication {
        application { installSalty(imageStore) }
        val resp = createClient { }.get("/login")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("Sign in"))
    }

    /** Build info moved into the app's About dialog, and is still not shown to anonymous visitors. */
    @Test
    fun buildInfoIsBehindAuth() = testApplication {
        application { installSalty(imageStore) }
        val anon = createClient { followRedirects = false }.get("/app")
        assertEquals(HttpStatusCode.Found, anon.status)
        assertEquals("/login", anon.headers[HttpHeaders.Location])

        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val html = web.get("/app").bodyAsText()
        assertTrue(html.contains("data-version="), "the app shell should carry the build info its About dialog shows")
    }

    /** Signing in lands on the app, not on the classic library it used to. */
    @Test
    fun loginRedirectsToTheApp() = testApplication {
        application { installSalty(imageStore) }
        val web = createClient { install(HttpCookies); followRedirects = false }
        val resp = web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertEquals("/app", resp.headers[HttpHeaders.Location])
    }

    /** `/` is a signpost to the app now; anonymous visitors still meet the login page first. */
    @Test
    fun rootRedirectsToTheApp() = testApplication {
        application { installSalty(imageStore) }
        val web = createClient { install(HttpCookies); followRedirects = false }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        assertEquals("/app", web.get("/").headers[HttpHeaders.Location])

        val anon = createClient { followRedirects = false }.get("/")
        assertEquals("/login", anon.headers[HttpHeaders.Location])
    }

    @Test
    fun classicRootRedirectsWhenNotLoggedIn() = testApplication {
        application { installSalty(imageStore) }
        val resp = createClient { followRedirects = false }.get("/classic")
        assertEquals(HttpStatusCode.Found, resp.status)
        assertEquals("/login", resp.headers[HttpHeaders.Location])
    }

    @Test
    fun webLoginThenListsRecipes() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            RecipeRepository.upsert(uid, recipe("w1", "Web Waffles", "2026-06-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertTrue(web.get("/classic").bodyAsText().contains("Web Waffles"))
    }

    @Test
    fun webShowsShoppingListsAndTheirContents() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "wl1", name = "Web Groceries", isFreeform = false,
                contentsForList = listOf(
                    ShoppingListListContents(id = "h1", isHeading = true, text = "Produce"),
                    ShoppingListListContents(id = "i1", isCompleted = true, text = "Web Apples"),
                    ShoppingListListContents(id = "i2", text = "Web Spinach"),
                ),
                lastModifiedDate = "2026-07-20T00:00:00.000Z",
            ))
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "wl2", name = "Web Notes", isFreeform = true,
                contentsForFreeform = "# Corner Store\n* Milk",
                lastModifiedDate = "2026-07-20T00:00:00.000Z",
            ))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )

        val index = web.get("/classic/shoppingLists").bodyAsText()
        assertTrue(index.contains("Web Groceries"))
        assertTrue(index.contains("Web Notes"))
        // Size-based, matching the Swift app's row subtitle: headings don't count as items, and blank
        // lines don't count as lines. wl1 has a heading + 2 items; wl2 has 2 non-blank lines.
        assertTrue(index.contains("2 items"), "checklist reports its item count")
        assertTrue(index.contains("2 lines"), "freeform list reports its line count")

        val checklist = web.get("/classic/shoppingLists/wl1").bodyAsText()
        assertTrue(checklist.contains("Produce"))
        assertTrue(checklist.contains("Web Apples"))
        assertTrue(checklist.contains("☑"), "completed items render as checked")
        assertTrue(checklist.contains("☐"), "open items render as unchecked")

        val freeform = web.get("/classic/shoppingLists/wl2").bodyAsText()
        assertTrue(freeform.contains("Corner Store"))

        // The sidebar entry must appear on OTHER pages too — that's what makes the section reachable
        // at all. Asserting it only on its own page would pass even if it were never added to chrome().
        val recipesPage = web.get("/classic").bodyAsText()
        assertTrue(recipesPage.contains("Shopping Lists"), "sidebar entry missing from the recipes page")
        assertTrue(recipesPage.contains("href=\"/classic/shoppingLists\""), "sidebar link missing from the recipes page")
    }

    @Test
    fun webShoppingListSummaryHandlesSingularAndEmpty() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "one", name = "One Item", isFreeform = false,
                contentsForList = listOf(ShoppingListListContents(id = "i", text = "Milk")),
                lastModifiedDate = "2026-07-20T00:00:00.000Z"))
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "headings", name = "Headings Only", isFreeform = false,
                contentsForList = listOf(ShoppingListListContents(id = "h", isHeading = true, text = "Produce")),
                lastModifiedDate = "2026-07-20T00:00:00.000Z"))
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "blank", name = "Blank Freeform", isFreeform = true,
                contentsForFreeform = "\n\n   \n", lastModifiedDate = "2026-07-20T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val index = web.get("/classic/shoppingLists").bodyAsText()
        assertTrue(index.contains("1 item<"), "singular, not \"1 items\"")
        assertTrue(index.contains("No items"), "a headings-only list has no items to count")
        assertTrue(index.contains("Empty"), "whitespace-only freeform counts as empty")
    }

    /** The CSRF token lives in the session; forms echo it. Fish it out of a rendered page. */
    /**
     * The CSRF token the app shell hands its own JSON calls. The classic pages put it in a hidden
     * form field; the app puts it in a data attribute, and user management is an API now.
     */
    private suspend fun appCsrf(client: HttpClient): String =
        Regex("""data-csrf="([0-9a-f]+)"""").find(client.get("/app").bodyAsText())?.groupValues?.get(1)
            ?: error("no CSRF token in the app shell")

    private fun csrfFrom(html: String): String =
        Regex("""name="csrf" value="([0-9a-f]+)"""").find(html)?.groupValues?.get(1)
            ?: error("no CSRF token found in page")

    @Test
    fun webChecklistEditingRoundTrip() = testApplication {
        application { installSalty(imageStore) }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val csrf = csrfFrom(web.get("/classic/shoppingLists").bodyAsText())

        // Create a checklist from the index form.
        val created = web.submitForm(
            url = "/classic/shoppingLists",
            formParameters = parameters { append("csrf", csrf); append("name", "Web List"); append("type", "checklist") },
        )
        assertEquals(HttpStatusCode.Found, created.status)
        val listPath = created.headers[HttpHeaders.Location]!!
        val listId = listPath.substringAfterLast("/")

        // Add two items, one of them a heading.
        web.submitForm(url = "$listPath/items/add", formParameters = parameters {
            append("csrf", csrf); append("text", "Produce"); append("heading", "on")
        })
        web.submitForm(url = "$listPath/items/add", formParameters = parameters {
            append("csrf", csrf); append("text", "Apples")
        })
        var list = runBlocking {
            ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, listId)!!
        }
        assertEquals(listOf("Produce", "Apples"), list.contentsForList?.map { it.text })
        assertEquals(true, list.contentsForList?.first()?.isHeading)
        val revisionAfterAdds = list.revision!!
        assertTrue(revisionAfterAdds >= 3, "create + two adds must each bump the revision")

        // Toggle, edit, then delete the item — each one a semantic per-item POST.
        val itemId = list.contentsForList!![1].id
        web.submitForm(url = "$listPath/items/toggle", formParameters = parameters { append("csrf", csrf); append("itemId", itemId) })
        web.submitForm(url = "$listPath/items/edit", formParameters = parameters {
            append("csrf", csrf); append("itemId", itemId); append("text", "Green Apples")
        })
        list = runBlocking { ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, listId)!! }
        assertEquals(true, list.contentsForList?.get(1)?.isCompleted)
        assertEquals("Green Apples", list.contentsForList?.get(1)?.text)

        web.submitForm(url = "$listPath/items/delete", formParameters = parameters { append("csrf", csrf); append("itemId", itemId) })
        list = runBlocking { ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, listId)!! }
        assertEquals(listOf("Produce"), list.contentsForList?.map { it.text })

        // Rename, then delete the whole list.
        web.submitForm(url = "$listPath/rename", formParameters = parameters { append("csrf", csrf); append("name", "Renamed") })
        assertEquals("Renamed", runBlocking { ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, listId)?.name })
        web.submitForm(url = "$listPath/delete", formParameters = parameters { append("csrf", csrf) })
        assertEquals(null, runBlocking { ShoppingListRepository.getById(UserRepository.findByUsername("tester")!!.id, listId) })
    }

    /** ↑/↓ swap with the neighbor; moving past either end is a true no-op (revision untouched). */
    @Test
    fun webChecklistItemReordering() = testApplication {
        application { installSalty(imageStore) }
        val uid = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "ord", name = "Ordered", isFreeform = false,
                contentsForList = listOf(
                    ShoppingListListContents(id = "a", text = "Alpha"),
                    ShoppingListListContents(id = "b", text = "Beta"),
                    ShoppingListListContents(id = "c", text = "Gamma"),
                ),
                lastModifiedDate = "2026-08-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val csrf = csrfFrom(web.get("/classic/shoppingLists/ord").bodyAsText())
        suspend fun texts() = ShoppingListRepository.getById(uid, "ord")!!.contentsForList!!.map { it.text }
        suspend fun revision() = ShoppingListRepository.getById(uid, "ord")!!.revision

        web.submitForm(url = "/classic/shoppingLists/ord/items/move", formParameters = parameters {
            append("csrf", csrf); append("itemId", "c"); append("dir", "up")
        })
        assertEquals(listOf("Alpha", "Gamma", "Beta"), runBlocking { texts() })

        web.submitForm(url = "/classic/shoppingLists/ord/items/move", formParameters = parameters {
            append("csrf", csrf); append("itemId", "a"); append("dir", "down")
        })
        assertEquals(listOf("Gamma", "Alpha", "Beta"), runBlocking { texts() })

        // Top item up / bottom item down: order AND revision must be untouched — a no-op that still
        // bumped the revision would make every client re-download the list for nothing.
        val before = runBlocking { revision() }
        web.submitForm(url = "/classic/shoppingLists/ord/items/move", formParameters = parameters {
            append("csrf", csrf); append("itemId", "g"); append("dir", "up")   // unknown id: also a no-op
        })
        web.submitForm(url = "/classic/shoppingLists/ord/items/move", formParameters = parameters {
            append("csrf", csrf); append("itemId", "b"); append("dir", "down")
        })
        assertEquals(listOf("Gamma", "Alpha", "Beta"), runBlocking { texts() })
        assertEquals(before, runBlocking { revision() }, "no-op moves must not bump the revision")
    }

    /** A move against a row whose contentsForList is NULL (freeform lists by construction) must stay a
     *  no-op: NULL must never be materialized as [] — that distinction protects older clients (see
     *  ShoppingListRepository.write) — and the revision must not budge. */
    @Test
    fun webItemMoveOnNullContentsListIsANoOp() = testApplication {
        application { installSalty(imageStore) }
        val uid = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "ff", name = "Notes", isFreeform = true,
                contentsForFreeform = "milk\neggs",
                lastModifiedDate = "2026-08-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val csrf = csrfFrom(web.get("/classic/shoppingLists/ff").bodyAsText())
        val before = runBlocking { ShoppingListRepository.getById(uid, "ff")!! }

        web.submitForm(url = "/classic/shoppingLists/ff/items/move", formParameters = parameters {
            append("csrf", csrf); append("itemId", "x"); append("dir", "up")
        })

        val after = runBlocking { ShoppingListRepository.getById(uid, "ff")!! }
        assertEquals(null, after.contentsForList, "NULL contents must never become []")
        assertEquals(before.revision, after.revision, "no-op move must not bump the revision")
    }

    /** A freeform save whose baseRevision went stale must show the conflict banner, not clobber. */
    @Test
    fun webFreeformSaveConflictShowsBannerAndPreservesDraft() = testApplication {
        application { installSalty(imageStore) }
        val uid = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "ff", name = "Notes", isFreeform = true,
                contentsForFreeform = "original", lastModifiedDate = "2026-08-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val page = web.get("/classic/shoppingLists/ff").bodyAsText()
        val csrf = csrfFrom(page)
        assertTrue(page.contains("""name="baseRevision" value="1""""), "editor carries the revision it rendered")

        // A sync lands meanwhile (revision 1 → 2).
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "ff", name = "Notes", isFreeform = true,
                contentsForFreeform = "from a device", lastModifiedDate = "2026-08-02T00:00:00.000Z",
                baseRevision = 1))
        }

        // The stale tab saves: banner + both texts, and the row is untouched.
        val conflicted = web.submitForm(url = "/classic/shoppingLists/ff/freeform", formParameters = parameters {
            append("csrf", csrf); append("text", "my draft"); append("baseRevision", "1")
        }).bodyAsText()
        assertTrue(conflicted.contains("changed while you were editing"), "conflict banner shown")
        assertTrue(conflicted.contains("from a device"), "current saved version shown")
        assertTrue(conflicted.contains("my draft"), "draft preserved in the editor")
        assertTrue(conflicted.contains("""name="baseRevision" value="2""""), "retry targets the new revision")
        assertEquals("from a device", runBlocking { ShoppingListRepository.getById(uid, "ff")?.contentsForFreeform })

        // Retrying with the fresh baseRevision succeeds.
        val saved = web.submitForm(url = "/classic/shoppingLists/ff/freeform", formParameters = parameters {
            append("csrf", csrf); append("text", "my draft"); append("baseRevision", "2")
        })
        assertEquals(HttpStatusCode.Found, saved.status)
        assertEquals("my draft", runBlocking { ShoppingListRepository.getById(uid, "ff")?.contentsForFreeform })
    }

    /** Web mutations are state-changing form POSTs: no valid CSRF token, no write. */
    @Test
    fun webShoppingListEditsRequireCsrf() = testApplication {
        application { installSalty(imageStore) }
        val uid = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "sl", name = "Guarded", isFreeform = false,
                contentsForList = listOf(ShoppingListListContents(id = "i1", text = "Milk")),
                lastModifiedDate = "2026-08-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val resp = web.submitForm(url = "/classic/shoppingLists/sl/items/delete", formParameters = parameters {
            append("csrf", "forged"); append("itemId", "i1")
        })
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(1, runBlocking { ShoppingListRepository.getById(uid, "sl")?.contentsForList?.size })
    }

    /** One user must never see another's lists, and an unknown id must not 500. */
    @Test
    fun webShoppingListsAreUserScoped() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            UserRepository.create("other", "pw2")
            val otherId = UserRepository.findByUsername("other")!!.id
            ShoppingListRepository.save(otherId, ServerShoppingList(
                id = "secret", name = "Other Persons List",
                lastModifiedDate = "2026-07-20T00:00:00.000Z",
            ))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )

        assertTrue(!web.get("/classic/shoppingLists").bodyAsText().contains("Other Persons List"))
        // Unknown / not-yours id redirects back to the index rather than erroring.
        assertEquals(HttpStatusCode.OK, web.get("/classic/shoppingLists/secret").status)
        assertTrue(!web.get("/classic/shoppingLists/secret").bodyAsText().contains("Other Persons List"))
    }

    @Test
    fun webRecipesPaginate() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            // 30 zero-padded names so lexical sort == numeric: page 1 = 01..25, page 2 = 26..30.
            (1..30).forEach { i ->
                RecipeRepository.upsert(uid, recipe("r$i", "Recipe %02d".format(i), "2026-06-01T00:00:00.000Z"))
            }
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val page1 = web.get("/classic").bodyAsText()
        assertTrue(page1.contains("Page 1 of 2"), "page 1 shows pagination")
        assertTrue(page1.contains("Recipe 01") && page1.contains("Recipe 25"), "page 1 holds the first 25")
        assertTrue(!page1.contains("Recipe 26"), "page 1 stops at 25")

        val page2 = web.get("/classic?page=2").bodyAsText()
        assertTrue(page2.contains("Recipe 26") && page2.contains("Recipe 30"), "page 2 holds the remainder")
        assertTrue(!page2.contains("Recipe 01"), "page 2 excludes page-1 recipes")
    }

    @Test
    fun webBrowseByCourseAndCategory() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            LibraryRepository.upsertCourse(uid, ServerCourse("c-dessert", "Desserts", "2026-06-01T00:00:00.000Z"))
            LibraryRepository.upsertCategory(uid, ServerCategory("cat-quick", "Quick", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.upsert(uid, recipe("r1", "Brownies", "2026-06-01T00:00:00.000Z")
                .copy(courseId = "c-dessert", categoryIds = listOf("cat-quick")))
            RecipeRepository.upsert(uid, recipe("r2", "Pot Roast", "2026-06-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        // Browse indexes list the classifiers with a recipe count.
        val courses = web.get("/classic/courses").bodyAsText()
        assertTrue(courses.contains("Desserts"), "course index lists the course")

        // Drill-down filters to just that course/category.
        val inCourse = web.get("/classic/courses/c-dessert").bodyAsText()
        assertTrue(inCourse.contains("Brownies"), "course view includes its recipe")
        assertTrue(!inCourse.contains("Pot Roast"), "course view excludes other recipes")

        val inCategory = web.get("/classic/categories/cat-quick").bodyAsText()
        assertTrue(inCategory.contains("Brownies") && !inCategory.contains("Pot Roast"), "category view filters correctly")

        // Unknown ids redirect back to the browse index rather than erroring.
        val missing = createClient { install(HttpCookies); followRedirects = false }
        missing.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        assertEquals("/classic/tags", missing.get("/classic/tags/nope").headers[HttpHeaders.Location])
    }

    @Test
    fun nonAdminCannotAccessUserManagement() = testApplication {
        application { installSalty(imageStore) }
        // "tester" is seeded as a non-admin in reset().
        val web = jsonCookieClient()
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertEquals(HttpStatusCode.Forbidden, web.get("/api/users").status)
    }

    @Test
    fun adminCanCreateUserWithIsolatedDataThenDelete() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = jsonCookieClient()
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "boss"); append("password", "pw") },
        )
        // Admin can list users.
        assertEquals(HttpStatusCode.OK, web.get("/api/users").status)
        val csrf = appCsrf(web)

        // Create a new user (password must clear the 8-char minimum; CSRF header required).
        assertEquals(
            HttpStatusCode.Created,
            web.post("/api/users") {
                contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
                setBody(CreateUserRequest("alice", "alicepw12"))
            }.status,
        )
        val alice = runBlocking { UserRepository.findByUsername("alice") }
        assertNotNull(alice)
        assertTrue(!alice.isAdmin)

        // Alice's library is isolated: give boss a recipe, confirm alice's API view is empty.
        runBlocking {
            val bossId = UserRepository.findByUsername("boss")!!.id
            RecipeRepository.upsert(bossId, recipe("b1", "Boss Bread", "2026-06-01T00:00:00.000Z"))
        }
        val api = jsonClient()
        val aliceToken = runBlocking {
            api.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("alice", "alicepw12", deviceId = "alice-device"))
            }.body<AuthResponse>().deviceToken!!
        }
        assertEquals(0, runBlocking { api.get("/api/recipes") { bearerAuth(aliceToken) }.body<List<ServerRecipe>>().size })

        // Delete alice; she can no longer authenticate.
        web.delete("/api/users/${alice.id}") { header(CSRF_HEADER, csrf) }
        assertEquals(null, runBlocking { UserRepository.findByUsername("alice") })
    }

    @Test
    fun adminCannotDeleteOwnAccount() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = jsonCookieClient()
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "boss"); append("password", "pw") },
        )
        val bossId = runBlocking { UserRepository.findByUsername("boss")!!.id }
        // Send a valid CSRF token so the self-delete guard (not the CSRF check) is what blocks this.
        val csrf = appCsrf(web)
        val resp = web.delete("/api/users/$bossId") { header(CSRF_HEADER, csrf) }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertNotNull(runBlocking { UserRepository.findByUsername("boss") })
    }

    @Test
    fun loginSucceedsAndProtectsRoutes() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            // Bad creds → 401
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json); setBody(AuthRequest("tester", "wrong"))
                }.status,
            )
            // No token → 401
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes").status)
            // Good creds → token works
            val token = login(client)
            assertTrue(token.isNotBlank())
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun usernamesAreCaseInsensitive() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            // Mixed-case creation is stored lowercase; lookups match regardless of casing.
            assertEquals("mixedcase", UserRepository.create("MixedCase", "pw123456").username)
            assertNotNull(UserRepository.findByUsername("MIXEDCASE"))
            assertTrue(UserRepository.existsByUsername("mixedCASE"))
            // API login accepts any casing (and surrounding whitespace) and returns the canonical name.
            val resp = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest(" TESTER ", "pw", deviceId = "test-device"))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("tester", resp.body<AuthResponse>().username)
        }
    }

    @Test
    fun caseVariantUsernameIsRejectedAsDuplicate() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = jsonCookieClient()
        // Web login is case-insensitive too.
        web.submitForm(url = "/login", formParameters = parameters { append("username", "BOSS"); append("password", "pw") })
        val csrf = appCsrf(web)
        // "Tester" is a case variant of the existing "tester" — rejected as a duplicate, not created.
        val resp = web.post("/api/users") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(CreateUserRequest("Tester", "longenough1"))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals(2, runBlocking { UserRepository.listAll() }.size)
    }

    @Test
    fun recipeCrudRoundTripPreservesShapeAndDate() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val date = "2026-06-14T10:00:00.000Z"
            val created = client.post("/api/recipes") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(recipe("r1", "Pancakes", date))
            }
            assertEquals(HttpStatusCode.Created, created.status)

            val fetched = client.get("/api/recipes/r1") { bearerAuth(token) }.body<ServerRecipe>()
            assertEquals("Pancakes", fetched.name)
            assertEquals(date, fetched.lastModifiedDate) // exact wire date round-trip
            assertNotNull(fetched.categoryIds)

            client.put("/api/recipes/r1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(recipe("r1", "Pancakes v2", date))
            }
            assertEquals("Pancakes v2", client.get("/api/recipes/r1") { bearerAuth(token) }.body<ServerRecipe>().name)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/recipes/r1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/r1") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun shoppingListCrudRoundTripPreservesItemsAndDate() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val date = "2026-07-20T09:30:00.000Z"
            val list = ServerShoppingList(
                id = "sl1", name = "Groceries", isFreeform = false,
                contentsForList = listOf(
                    ShoppingListListContents(id = "h1", isHeading = true, text = "Produce"),
                    ShoppingListListContents(id = "i1", isCompleted = true, text = "Apples"),
                ),
                lastModifiedDate = date,
            )

            assertEquals(
                HttpStatusCode.Created,
                client.post("/api/shoppingLists") {
                    bearerAuth(token); contentType(ContentType.Application.Json); setBody(list)
                }.status,
            )

            val fetched = client.get("/api/shoppingLists/sl1") { bearerAuth(token) }.body<ServerShoppingList>()
            assertEquals("Groceries", fetched.name)
            assertEquals(false, fetched.isFreeform)
            assertEquals(date, fetched.lastModifiedDate)   // exact wire date round-trip
            // Item shape survives the JSON-text column, including the heading/completed flags.
            assertEquals(2, fetched.contentsForList?.size)
            assertEquals(true, fetched.contentsForList?.first()?.isHeading)
            assertEquals("Apples", fetched.contentsForList?.get(1)?.text)
            assertEquals(true, fetched.contentsForList?.get(1)?.isCompleted)

            client.put("/api/shoppingLists/sl1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(list.copy(name = "Groceries v2"))
            }
            assertEquals(
                "Groceries v2",
                client.get("/api/shoppingLists/sl1") { bearerAuth(token) }.body<ServerShoppingList>().name,
            )

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/shoppingLists/sl1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/shoppingLists/sl1") { bearerAuth(token) }.status)
        }
    }

    /** Clients detect deletions by absence from this list, so it must be complete and counted. */
    @Test
    fun shoppingListsListIsCompleteAndReportsTotalCount() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            for (i in 1..3) {
                client.post("/api/shoppingLists") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(ServerShoppingList(id = "sl$i", name = "List $i", lastModifiedDate = "2026-07-20T09:00:00.000Z"))
                }
            }
            val resp = client.get("/api/shoppingLists") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("3", resp.headers["X-Total-Count"])
            assertEquals(3, resp.body<List<ServerShoppingList>>().size)
        }
    }

    @Test
    fun shoppingListsAreUserScopedAndBehindAuth() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/shoppingLists").status)
            val token = login(client)
            assertEquals(HttpStatusCode.OK, client.get("/api/shoppingLists") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun shoppingListRevisionStartsAtOneAndIncrementsOnMatchedSave() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val created = client.post("/api/shoppingLists") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "rev1", name = "v1", lastModifiedDate = "2026-08-01T00:00:00.000Z"))
            }.body<ServerShoppingList>()
            assertEquals(1L, created.revision)

            val updated = client.put("/api/shoppingLists/rev1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "rev1", name = "v2",
                    lastModifiedDate = "2026-08-02T00:00:00.000Z", baseRevision = 1))
            }.body<ServerShoppingList>()
            assertEquals(2L, updated.revision)
            assertEquals(2L, client.get("/api/shoppingLists/rev1") { bearerAuth(token) }.body<ServerShoppingList>().revision)
        }
    }

    /** A stale baseRevision means the row changed hands since that client synced: 409 + current row. */
    @Test
    fun shoppingListBaseRevisionMismatchIsRejectedWithCurrentRow() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            client.post("/api/shoppingLists") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "c1", name = "server truth", lastModifiedDate = "2026-08-01T00:00:00.000Z"))
            }
            val resp = client.put("/api/shoppingLists/c1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "c1", name = "stale attempt",
                    lastModifiedDate = "2026-08-05T00:00:00.000Z", baseRevision = 99))
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
            // The 409 body IS the merge input — it must be the current server row.
            assertEquals("server truth", resp.body<ServerShoppingList>().name)
            assertEquals("server truth", client.get("/api/shoppingLists/c1") { bearerAuth(token) }.body<ServerShoppingList>().name)
        }
    }

    /**
     * Legacy clients (no baseRevision) keep last-writer-wins, but guarded: an OLDER write is ignored
     * — yet still answered 2xx with the winning row, because legacy clients abort their whole sync
     * on any error status.
     */
    @Test
    fun shoppingListLegacyWritesAreTimestampGuarded() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            client.post("/api/shoppingLists") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "lg1", name = "current", lastModifiedDate = "2026-08-10T00:00:00.000Z"))
            }
            // Stale legacy write: 2xx, but ignored — the response carries the winner, not the echo.
            val stale = client.put("/api/shoppingLists/lg1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "lg1", name = "stale", lastModifiedDate = "2026-08-01T00:00:00.000Z"))
            }
            assertEquals(HttpStatusCode.OK, stale.status)
            assertEquals("current", stale.body<ServerShoppingList>().name)
            val afterStale = client.get("/api/shoppingLists/lg1") { bearerAuth(token) }.body<ServerShoppingList>()
            assertEquals("current", afterStale.name)
            assertEquals(1L, afterStale.revision, "an ignored write must not bump the revision")

            // Newer legacy write: applied, and it bumps the revision so revision-aware clients see it.
            client.put("/api/shoppingLists/lg1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "lg1", name = "newer", lastModifiedDate = "2026-08-11T00:00:00.000Z"))
            }
            val afterNewer = client.get("/api/shoppingLists/lg1") { bearerAuth(token) }.body<ServerShoppingList>()
            assertEquals("newer", afterNewer.name)
            assertEquals(2L, afterNewer.revision)
        }
    }

    /** Delete with If-Match: refused (409 + row) when the row moved on — edit beats delete. */
    @Test
    fun shoppingListDeleteHonorsIfMatchRevision() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            client.post("/api/shoppingLists") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "dl1", name = "keep me", lastModifiedDate = "2026-08-01T00:00:00.000Z"))
            }
            client.put("/api/shoppingLists/dl1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(ServerShoppingList(id = "dl1", name = "edited meanwhile",
                    lastModifiedDate = "2026-08-02T00:00:00.000Z", baseRevision = 1))
            }
            val refused = client.delete("/api/shoppingLists/dl1") { bearerAuth(token); header(HttpHeaders.IfMatch, "1") }
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("edited meanwhile", refused.body<ServerShoppingList>().name)

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/shoppingLists/dl1") { bearerAuth(token); header(HttpHeaders.IfMatch, "2") }.status,
            )
        }
    }

    @Test
    fun listReportsTotalCountNoParams() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b")
            val resp = client.get("/api/recipes") { bearerAuth(token) }
            assertEquals("2", resp.header("X-Total-Count"))
            assertEquals(2, resp.body<List<ServerRecipe>>().size)
        }
    }

    @Test
    fun modifiedSinceReturnsOnlyTheDelta() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            post(client, token, recipe("old", "Old", "2026-01-01T00:00:00.000Z"))
            post(client, token, recipe("new", "New", "2026-06-01T00:00:00.000Z"))
            val resp = client.get("/api/recipes?modifiedSince=2026-03-01T00:00:00.000Z") { bearerAuth(token) }
            val items = resp.body<List<ServerRecipe>>()
            assertEquals(1, items.size)
            assertEquals("new", items.first().id)
            assertEquals("1", resp.header("X-Total-Count"))
        }
    }

    @Test
    fun paginationReturnsPageAndHeaders() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b", "c")
            val resp = client.get("/api/recipes?page=0&size=2") { bearerAuth(token) }
            assertEquals(2, resp.body<List<ServerRecipe>>().size)
            assertEquals("3", resp.header("X-Total-Count"))
            assertEquals("2", resp.header("X-Total-Pages"))
            assertEquals("0", resp.header("X-Page-Number"))
        }
    }

    @Test
    fun manifestListsAllIds() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b")
            val resp = client.get("/api/recipes/sync/manifest") { bearerAuth(token) }
            assertEquals("2", resp.header("X-Total-Count"))
            assertEquals(setOf("a", "b"), resp.body<List<RecipeManifestEntry>>().map { it.id }.toSet())
        }
    }

    @Test
    fun badModifiedSinceReturns400() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/recipes?modifiedSince=not-a-date") { bearerAuth(token) }.status,
            )
        }
    }

    /**
     * What flips `isFirstSync` is COMPLETING a sync, not registering.
     *
     * It used to be registering, back when that was the only thing that created a `device_sync` row.
     * Enrolment creates one too now, so the old rule called a client that had merely signed in a
     * returning device — with no watermark to return to, which is the exact input SYNC-006 exists to
     * keep away from deletion inference. Registering twice is the same situation in miniature and is
     * pinned here alongside it: a client whose first sync died before `/complete` must get the same
     * protection on its retry.
     */
    @Test
    fun deviceRegistrationTracksFirstSync() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()

        // The device the token was issued for, because a token may only act on its own device. The
        // helper enrols as "test-device"; naming anything else here is now a 403.
        suspend fun register() = client.post("/api/recipes/sync/device") {
            bearerAuth(login(client)); contentType(ContentType.Application.Json)
            setBody(DeviceRegisterRequest("test-device", "Test Phone"))
        }.body<DeviceSyncInfo>()

        runBlocking {
            assertTrue(register().isFirstSync)

            val second = register()
            assertTrue(second.isFirstSync, "registering again is not syncing; nothing has been agreed yet")
            assertNull(second.lastSyncDate, "and the flag must agree with the watermark it stands for")

            client.post("/api/recipes/sync/device/test-device/complete") { bearerAuth(login(client)) }

            val afterSync = register()
            assertFalse(afterSync.isFirstSync, "a finished sync is what makes the device a returning one")
            assertNotNull(afterSync.lastSyncDate)
        }
    }

    /**
     * The regression device sync tokens introduced, end to end: enrolling writes the token into the
     * same `device_sync` row that carries the watermark, so the row exists before the client has
     * synced anything. The server must still call that a first sync.
     */
    @Test
    fun enrolmentDoesNotMakeADeviceLookLikeItHasAlreadySynced() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val auth = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AuthRequest("tester", "pw", deviceId = "device-enrolled", deviceName = "Laptop"))
            }.body<AuthResponse>()
            assertNotNull(auth.deviceToken, "the sign-in enrolled, which is what creates the row early")

            val info = client.post("/api/recipes/sync/device") {
                bearerAuth(auth.deviceToken!!); contentType(ContentType.Application.Json)
                setBody(DeviceRegisterRequest("device-enrolled", "Laptop"))
            }.body<DeviceSyncInfo>()

            assertTrue(info.isFirstSync, "enrolling is not syncing: this device has agreed nothing yet")
            assertNull(info.lastSyncDate)
        }
    }

    @Test
    fun thumbnailEndpointDownscalesImage() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            // Image endpoints are owner-scoped, so attach the stored image to a recipe owned by "tester".
            val uid = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("thumbtest", renderPng(1000, 800))
            RecipeRepository.upsert(uid, recipe("thumbtest", "Thumb", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(uid, "thumbtest", filename, null)

            val thumb = client.get("/api/recipes/images/$filename/thumbnail") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, thumb.status)
            val thumbBytes = thumb.body<ByteArray>()
            val decoded = ImageIO.read(ByteArrayInputStream(thumbBytes))
            assertNotNull(decoded)
            assertTrue(maxOf(decoded.width, decoded.height) <= ImageStore.THUMB_SIZE)
            // A 300px thumbnail must be far smaller than the ~1000px source on the wire.
            assertTrue(thumbBytes.size < imageStore.load(filename)!!.size)
        }
    }

    @Test
    fun headRequestsAnswerExistenceChecks() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            // Recipe existence (client uses HEAD to choose create-vs-update).
            post(client, token, recipe("r1", "Pancakes", "2026-06-14T10:00:00.000Z"))
            assertEquals(HttpStatusCode.OK, client.head("/api/recipes/r1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.head("/api/recipes/missing") { bearerAuth(token) }.status)

            // Image existence (client uses HEAD to avoid re-uploading an image already on the server).
            // Owner-scoped, so bind the stored image to a recipe owned by "tester".
            val uid = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("headtest", renderPng(40, 40))
            RecipeRepository.upsert(uid, recipe("headtest", "Head", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(uid, "headtest", filename, null)
            assertEquals(HttpStatusCode.OK, client.head("/api/recipes/images/$filename") { bearerAuth(token) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.head("/api/recipes/images/nope.jpg") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun aBodyUploadNamingAnImageTheServerDoesNotHaveLeavesTheRealOneAlone() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val uid = UserRepository.findByUsername("tester")!!.id

            // The server holds "<id>.png" — the state after any client that converts on upload, which
            // every client now does for a format the server can't serve.
            post(client, token, recipe("conv", "Converted", "2026-06-01T00:00:00.000Z"))
            val stored = imageStore.store("conv", renderPng(40, 40))
            RecipeRepository.setImageFilename(uid, "conv", stored, WireDate.parse("2026-06-02T00:00:00.000Z"))

            // A later body edit from that client still carries ITS local name, with a newer image stamp.
            // Honouring it used to point the row at a file that doesn't exist AND delete the real one.
            post(
                client, token,
                recipe("conv", "Converted, edited", "2026-06-03T00:00:00.000Z")
                    .copy(imageFilename = "conv.heic", lastModifiedImageDate = "2026-06-03T00:00:00.000Z"),
            )

            assertEquals(stored, RecipeRepository.imageFilename(uid, "conv"), "the stored name must survive")
            assertTrue(imageStore.exists(stored), "the real image file must not be deleted")
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes/images/$stored") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun aBodyUploadCanStillClearAnImage() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val uid = UserRepository.findByUsername("tester")!!.id

            post(client, token, recipe("clr", "Clearable", "2026-06-01T00:00:00.000Z"))
            val stored = imageStore.store("clr", renderPng(40, 40))
            RecipeRepository.setImageFilename(uid, "clr", stored, WireDate.parse("2026-06-02T00:00:00.000Z"))

            // A null filename with a newer stamp is how an image REMOVAL rides a body upload; ignoring
            // unknown names must not have broken that.
            post(
                client, token,
                recipe("clr", "Clearable", "2026-06-03T00:00:00.000Z")
                    .copy(imageFilename = null, lastModifiedImageDate = "2026-06-03T00:00:00.000Z"),
            )

            assertEquals(null, RecipeRepository.imageFilename(uid, "clr"), "an explicit removal still applies")
        }
    }

    @Test
    fun anImageUploadIsStoredByItsRealFormatAndUnservableOnesAreRefused() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val uid = UserRepository.findByUsername("tester")!!.id
            post(client, token, recipe("fmt", "Format", "2026-06-01T00:00:00.000Z"))

            // Both uploads announce themselves as JPEG: the label is what the server used to trust, and
            // is exactly what it must now ignore in favour of the bytes.
            suspend fun upload(bytes: ByteArray) = client.submitFormWithBinaryData(
                url = "/api/recipes/fmt/image",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"fmt.jpg\"")
                    })
                },
            ) { bearerAuth(token) }

            // PNG bytes announced as JPEG: the server used to believe the label and write "<id>.jpg".
            assertEquals(HttpStatusCode.OK, upload(renderPng(40, 40)).status)
            assertEquals("fmt.png", RecipeRepository.imageFilename(uid, "fmt"), "the bytes decide the extension")

            // A format the server can't serve is refused outright rather than stored unreadably.
            val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(16)
            assertEquals(HttpStatusCode.UnsupportedMediaType, upload(heic).status)
            assertEquals(
                "fmt.png",
                RecipeRepository.imageFilename(uid, "fmt"),
                "a refused upload must not disturb the image already stored",
            )
        }
    }

    @Test
    fun cannotReadAnotherUsersImage() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            // "tester" owns a recipe with an image.
            val tester = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("victim", renderPng(60, 60))
            RecipeRepository.upsert(tester, recipe("victim", "Secret", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(tester, "victim", filename, null)

            // A different user must not be able to read it by filename (GET/HEAD/thumbnail all 404).
            UserRepository.create("intruder", "pw")
            val intruderToken = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("intruder", "pw", deviceId = "intruder-device"))
            }.body<AuthResponse>().deviceToken!!

            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/images/$filename") { bearerAuth(intruderToken) }.status)
            assertEquals(HttpStatusCode.NotFound, client.head("/api/recipes/images/$filename") { bearerAuth(intruderToken) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/images/$filename/thumbnail") { bearerAuth(intruderToken) }.status)

            // The owner still can.
            val testerToken = login(client)
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes/images/$filename") { bearerAuth(testerToken) }.status)
        }
    }

    @Test
    fun tokenInvalidatedByPasswordChangeAndDeletion() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            UserRepository.create("carol", "carolpw12")
            val token = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("carol", "carolpw12", deviceId = "carol-device"))
            }.body<AuthResponse>().deviceToken!!
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token) }.status)

            // Cross a whole second so the password-change timestamp is strictly after the token's iat
            // (iat has second granularity), then the old token must be rejected.
            kotlinx.coroutines.delay(1100)
            val carol = UserRepository.findByUsername("carol")!!
            UserRepository.changePassword(carol.id, "carolpw34")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes") { bearerAuth(token) }.status)

            // A token for a since-deleted user is likewise rejected (existence check, no timing needed).
            val token2 = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("carol", "carolpw34", deviceId = "carol-device"))
            }.body<AuthResponse>().deviceToken!!
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token2) }.status)
            UserRepository.deleteWithData(carol.id)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes") { bearerAuth(token2) }.status)
        }
    }

    @Test
    fun adminPostWithoutCsrfIsRejected() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "boss"); append("password", "pw") })
        // No CSRF header → 403, and no user is created.
        val resp = web.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest("mallory", "password123"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(null, runBlocking { UserRepository.findByUsername("mallory") })
    }

    @Test
    fun createUserRejectsShortPassword() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "boss"); append("password", "pw") })
        val csrf = appCsrf(web)
        val resp = web.post("/api/users") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(CreateUserRequest("shorty", "abc"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(null, runBlocking { UserRepository.findByUsername("shorty") })
    }

    @Test
    fun distributedFailuresLockTheAccountAcrossIps() = testApplication {
        // Low account threshold; trustProxy so each request's X-Forwarded-For becomes the client IP the
        // per-IP throttle sees.
        val lockout = AccountLockout(maxFailures = 3, lockMs = 60_000L)
        application { installSalty(imageStore, trustProxy = true, accountLockout = lockout) }
        val client = jsonClient()
        runBlocking {
            // 3 failures, each from a DIFFERENT IP — the per-IP throttle (threshold 10) never trips, but the
            // per-username lockout counts them all.
            repeat(3) { i ->
                val r = client.post("/api/auth/login") {
                    header(HttpHeaders.XForwardedFor, "203.0.113.$i")
                    contentType(ContentType.Application.Json); setBody(AuthRequest("tester", "wrong"))
                }
                assertEquals(HttpStatusCode.Unauthorized, r.status)
            }
            // Now globally locked: a further attempt is refused even from a fresh IP with the CORRECT password,
            // proving the lock is checked before credentials.
            val locked = client.post("/api/auth/login") {
                header(HttpHeaders.XForwardedFor, "203.0.113.99")
                contentType(ContentType.Application.Json); setBody(AuthRequest("tester", "pw"))
            }
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        }
    }

    @Test
    fun loginWithUnknownUsernameReturns401() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        val resp = runBlocking {
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("ghost", "whatever"))
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun oversizedRequestBodyRejectedPreAuth() = testApplication {
        // Tiny cap so a normal login body passes but an inflated one is rejected before any handler runs.
        application { installSalty(imageStore, maxRequestBodyBytes = 64) }
        val client = jsonClient()
        val resp = runBlocking {
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AuthRequest("a".repeat(500), "pw"))
            }
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun multipartImageUploadIsExemptFromBodyLimit() = testApplication {
        // Body cap far below the image bytes: multipart uploads must be exempt (they self-limit by size
        // and resolution), so a normal image still stores.
        application { installSalty(imageStore, maxRequestBodyBytes = 64) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val uid = UserRepository.findByUsername("tester")!!.id
            RecipeRepository.upsert(uid, recipe("img1", "Img", "2026-06-01T00:00:00.000Z"))
            val resp = client.submitFormWithBinaryData(
                url = "/api/recipes/img1/image",
                formData = formData {
                    append("file", renderPng(50, 50), Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"img1.png\"")
                    })
                },
            ) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    private fun renderPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    // helpers
    private suspend fun post(client: io.ktor.client.HttpClient, token: String, r: ServerRecipe) {
        client.post("/api/recipes") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(r)
        }
    }

    private suspend fun seed(client: io.ktor.client.HttpClient, token: String, vararg ids: String) {
        ids.forEachIndexed { i, id ->
            post(client, token, recipe(id, "Recipe $id", "2026-06-0${i + 1}T00:00:00.000Z"))
        }
    }

    private fun HttpResponse.header(name: String): String? = headers[name]
}
