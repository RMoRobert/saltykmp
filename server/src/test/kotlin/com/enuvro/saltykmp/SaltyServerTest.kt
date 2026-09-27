package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.LibraryDeleteRequest
import com.enuvro.saltykmp.api.LibraryDeleteResponse
import com.enuvro.saltykmp.api.LibraryMergeRequest
import com.enuvro.saltykmp.api.LibraryMergeResponse
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.api.ServerTag
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
import org.jetbrains.exposed.v1.jdbc.selectAll
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

    // ---- Merging classifiers ----
    //
    // The merge endpoint is the web app's stand-in for the fold the native clients run locally. The
    // contract that makes it safe to sync is: every recipe that referenced a duplicate is re-pointed
    // at the survivor AND has its lastModifiedDate moved to now (so a client sees a newer row and
    // downloads it), while the survivor and every untouched recipe keep their stamps exactly (so
    // nothing else is re-transferred, and no client's newer edit is clobbered for no reason).

    private fun testerId() = runBlocking { UserRepository.findByUsername("tester")!!.id }

    @Test
    fun mergingCategoriesRepointsRecipesAndMovesOnlyTheirStamps() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        runBlocking {
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-keep", "Desserts", "2026-01-01T00:00:00.000Z"))
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-dup1", "desserts", "2026-01-02T00:00:00.000Z"))
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-dup2", "Deserts", "2026-01-03T00:00:00.000Z"))
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-other", "Breads", "2026-01-04T00:00:00.000Z"))
            RecipeRepository.upsert(userId, recipe("r-dup", "Pie", "2026-02-01T00:00:00.000Z").copy(categoryIds = listOf("cat-dup1")))
            // Holds the survivor AND a duplicate: must end up with the survivor once, not twice.
            RecipeRepository.upsert(userId, recipe("r-both", "Tart", "2026-02-01T00:00:00.000Z").copy(categoryIds = listOf("cat-keep", "cat-dup2")))
            RecipeRepository.upsert(userId, recipe("r-keep", "Cake", "2026-02-01T00:00:00.000Z").copy(categoryIds = listOf("cat-keep")))
            RecipeRepository.upsert(userId, recipe("r-none", "Bread", "2026-02-01T00:00:00.000Z").copy(categoryIds = listOf("cat-other")))
        }
        val client = jsonClient()
        val token = login(client)
        val resp = client.post("/api/categories/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            // A duplicate that is already gone is skipped, and the survivor naming itself is ignored.
            setBody(LibraryMergeRequest("cat-keep", listOf("cat-dup1", "cat-dup2", "cat-gone", "cat-keep")))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val result = resp.body<LibraryMergeResponse>()
        assertEquals("cat-keep", result.survivorId)
        assertEquals(listOf("cat-dup1", "cat-dup2"), result.removedIds)
        assertEquals(setOf("r-dup", "r-both"), result.touchedRecipeIds.toSet())

        val categories = runBlocking { LibraryRepository.listCategories(userId) }
        assertEquals(setOf("cat-keep", "cat-other"), categories.map { it.id }.toSet(), "the duplicates are gone, nothing else is")
        assertEquals("2026-01-01T00:00:00.000Z", categories.first { it.id == "cat-keep" }.lastModifiedDate, "the survivor is not restamped")

        val byId = listOf("r-dup", "r-both", "r-keep", "r-none").associateWith { runBlocking { RecipeRepository.getById(userId, it)!! } }
        assertEquals(listOf("cat-keep"), byId["r-dup"]!!.categoryIds)
        assertEquals(listOf("cat-keep"), byId["r-both"]!!.categoryIds, "one survivor row, not two")
        assertEquals(listOf("cat-keep"), byId["r-keep"]!!.categoryIds)
        assertEquals(listOf("cat-other"), byId["r-none"]!!.categoryIds)
        val before = "2026-02-01T00:00:00.000Z"
        assertTrue(byId["r-dup"]!!.lastModifiedDate!! > before, "a re-pointed recipe's stamp moves, so clients download it")
        assertTrue(byId["r-both"]!!.lastModifiedDate!! > before)
        assertEquals(before, byId["r-keep"]!!.lastModifiedDate, "a recipe that only ever held the survivor is untouched")
        assertEquals(before, byId["r-none"]!!.lastModifiedDate)

        // The manifest is what a syncing client actually reads, so the moved stamps must show there.
        val manifest = client.get("/api/recipes/sync/manifest") { bearerAuth(token) }.body<List<RecipeManifestEntry>>()
            .associateBy { it.id }
        assertTrue(manifest["r-dup"]!!.lastModifiedDate!! > before)
        assertEquals(before, manifest["r-keep"]!!.lastModifiedDate)

        // No junction row may still name a deleted category.
        val dangling = runBlocking {
            DatabaseFactory.dbQuery {
                RecipeCategories.selectAll().count { it[RecipeCategories.categoryId] in setOf("cat-dup1", "cat-dup2") }
            }
        }
        assertEquals(0, dangling)
    }

    @Test
    fun mergingCoursesRepointsTheColumnAndTagsTheJunction() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        runBlocking {
            LibraryRepository.upsertCourse(userId, ServerCourse("c-keep", "Main", "2026-01-01T00:00:00.000Z"))
            LibraryRepository.upsertCourse(userId, ServerCourse("c-dup", "Mains", "2026-01-02T00:00:00.000Z"))
            LibraryRepository.upsertTag(userId, ServerTag("t-keep", "Quick", "2026-01-01T00:00:00.000Z"))
            LibraryRepository.upsertTag(userId, ServerTag("t-dup", "quick", "2026-01-02T00:00:00.000Z"))
            RecipeRepository.upsert(userId, recipe("r-a", "Stew", "2026-02-01T00:00:00.000Z").copy(courseId = "c-dup", tagIds = listOf("t-dup")))
            RecipeRepository.upsert(userId, recipe("r-b", "Roast", "2026-02-01T00:00:00.000Z").copy(courseId = "c-keep", tagIds = listOf("t-keep", "t-dup")))
            RecipeRepository.upsert(userId, recipe("r-c", "Salad", "2026-02-01T00:00:00.000Z").copy(courseId = null, tagIds = listOf("t-keep")))
        }
        val client = jsonClient()
        val token = login(client)

        val courses = client.post("/api/courses/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("c-keep", listOf("c-dup")))
        }
        assertEquals(HttpStatusCode.OK, courses.status, courses.bodyAsText())
        assertEquals(listOf("r-a"), courses.body<LibraryMergeResponse>().touchedRecipeIds)
        assertEquals(listOf("c-keep"), runBlocking { LibraryRepository.listCourses(userId) }.map { it.id })

        val tags = client.post("/api/tags/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("t-keep", listOf("t-dup")))
        }
        assertEquals(HttpStatusCode.OK, tags.status, tags.bodyAsText())
        assertEquals(setOf("r-a", "r-b"), tags.body<LibraryMergeResponse>().touchedRecipeIds.toSet())
        assertEquals(listOf("t-keep"), runBlocking { LibraryRepository.listTags(userId) }.map { it.id })

        val a = runBlocking { RecipeRepository.getById(userId, "r-a")!! }
        val b = runBlocking { RecipeRepository.getById(userId, "r-b")!! }
        val c = runBlocking { RecipeRepository.getById(userId, "r-c")!! }
        assertEquals("c-keep", a.courseId)
        assertEquals(listOf("t-keep"), a.tagIds)
        assertEquals("c-keep", b.courseId)
        assertEquals(listOf("t-keep"), b.tagIds, "one survivor row, not two")
        assertNull(c.courseId)
        assertEquals(listOf("t-keep"), c.tagIds)
        val before = "2026-02-01T00:00:00.000Z"
        assertTrue(a.lastModifiedDate!! > before)
        assertTrue(b.lastModifiedDate!! > before, "losing a duplicate tag is a change the other devices need")
        assertEquals(before, c.lastModifiedDate, "a recipe no merge touched keeps its stamp")
    }

    /** Another account's rows are invisible to a merge: neither a survivor nor a duplicate. */
    @Test
    fun mergeStaysInsideTheCallersLibrary() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        val bobId = runBlocking { UserRepository.create("bob", "pw").id }
        runBlocking {
            LibraryRepository.upsertTag(userId, ServerTag("t-mine", "Quick", "2026-01-01T00:00:00.000Z"))
            LibraryRepository.upsertTag(userId, ServerTag("t-mine2", "quick", "2026-01-01T00:00:00.000Z"))
            LibraryRepository.upsertTag(bobId, ServerTag("t-bob", "quick", "2026-01-01T00:00:00.000Z"))
            RecipeRepository.upsert(bobId, recipe("r-bob", "Bob's", "2026-02-01T00:00:00.000Z").copy(tagIds = listOf("t-bob")))
        }
        val client = jsonClient()
        val token = login(client)

        // Bob's tag named as a duplicate: skipped, and Bob's library is exactly as it was.
        val resp = client.post("/api/tags/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("t-mine", listOf("t-mine2", "t-bob")))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(listOf("t-mine2"), resp.body<LibraryMergeResponse>().removedIds)
        assertEquals(listOf("t-bob"), runBlocking { LibraryRepository.listTags(bobId) }.map { it.id })
        val bobs = runBlocking { RecipeRepository.getById(bobId, "r-bob")!! }
        assertEquals(listOf("t-bob"), bobs.tagIds)
        assertEquals("2026-02-01T00:00:00.000Z", bobs.lastModifiedDate)

        // Bob's tag as the survivor: there is nothing of the caller's to fold into.
        val foreign = client.post("/api/tags/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("t-bob", listOf("t-mine")))
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(listOf("t-mine"), runBlocking { LibraryRepository.listTags(userId) }.map { it.id })

        // An id that could not name a row is refused before anything is read.
        val bad = client.post("/api/tags/merge") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("t-mine", listOf("../etc")))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    /** The browser's merge is a session-cookie write, so it needs the CSRF header like every other. */
    @Test
    fun mergeFromTheBrowserNeedsCsrf() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        runBlocking {
            LibraryRepository.upsertCourse(userId, ServerCourse("c-keep", "Main"))
            LibraryRepository.upsertCourse(userId, ServerCourse("c-dup", "Mains"))
        }
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val without = web.post("/api/courses/merge") {
            contentType(ContentType.Application.Json)
            setBody(LibraryMergeRequest("c-keep", listOf("c-dup")))
        }
        assertEquals(HttpStatusCode.Forbidden, without.status)
        assertEquals(2, runBlocking { LibraryRepository.countCourses(userId) }, "a refused merge changes nothing")

        val with = web.post("/api/courses/merge") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, appCsrf(web))
            setBody(LibraryMergeRequest("c-keep", listOf("c-dup")))
        }
        assertEquals(HttpStatusCode.OK, with.status, with.bodyAsText())
        assertEquals(listOf("c-keep"), runBlocking { LibraryRepository.listCourses(userId) }.map { it.id })
    }

    // ---- Deleting several classifiers ----
    //
    // Same sync contract as the merge: the recipes' side is done here, and only the recipes that
    // lost something are restamped.

    @Test
    fun bulkDeletingClassifiersClearsRecipesAndMovesOnlyTheirStamps() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        val before = "2026-02-01T00:00:00.000Z"
        runBlocking {
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-a", "Desserts"))
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-b", "Cakes"))
            LibraryRepository.upsertCategory(userId, ServerCategory("cat-c", "Breads"))
            LibraryRepository.upsertCourse(userId, ServerCourse("c-a", "Main"))
            LibraryRepository.upsertCourse(userId, ServerCourse("c-b", "Side"))
            LibraryRepository.upsertTag(userId, ServerTag("t-a", "Quick"))
            RecipeRepository.upsert(userId, recipe("r1", "Pie", before).copy(courseId = "c-a", categoryIds = listOf("cat-a")))
            RecipeRepository.upsert(userId, recipe("r2", "Tart", before).copy(categoryIds = listOf("cat-a", "cat-b", "cat-c")))
            RecipeRepository.upsert(userId, recipe("r3", "Loaf", before).copy(categoryIds = listOf("cat-c"), tagIds = listOf("t-a")))
        }
        val client = jsonClient()
        val token = login(client)

        val cats = client.post("/api/categories/delete") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            // One already gone: skipped, not failed.
            setBody(LibraryDeleteRequest(listOf("cat-a", "cat-b", "cat-gone")))
        }
        assertEquals(HttpStatusCode.OK, cats.status, cats.bodyAsText())
        val catResult = cats.body<LibraryDeleteResponse>()
        assertEquals(listOf("cat-a", "cat-b"), catResult.removedIds)
        assertEquals(setOf("r1", "r2"), catResult.touchedRecipeIds.toSet(), "a recipe that lost two rows is listed once")
        assertEquals(listOf("cat-c"), runBlocking { LibraryRepository.listCategories(userId) }.map { it.id })
        val r1 = runBlocking { RecipeRepository.getById(userId, "r1")!! }
        val r2 = runBlocking { RecipeRepository.getById(userId, "r2")!! }
        var r3 = runBlocking { RecipeRepository.getById(userId, "r3")!! }
        assertEquals(emptyList(), r1.categoryIds)
        assertEquals(listOf("cat-c"), r2.categoryIds, "the category not deleted stays on the recipe")
        assertEquals(listOf("cat-c"), r3.categoryIds)
        assertTrue(r1.lastModifiedDate!! > before, "a recipe that lost a category is restamped, so clients download it")
        assertTrue(r2.lastModifiedDate!! > before)
        assertEquals(before, r3.lastModifiedDate, "a recipe that lost nothing keeps its stamp")
        val dangling = runBlocking {
            DatabaseFactory.dbQuery { RecipeCategories.selectAll().count { it[RecipeCategories.categoryId] in setOf("cat-a", "cat-b") } }
        }
        assertEquals(0, dangling, "no junction row survives naming a deleted category")

        // Courses: the column is cleared, and the last course can go (no guard against an empty list here).
        val courses = client.post("/api/courses/delete") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryDeleteRequest(listOf("c-a", "c-b")))
        }
        assertEquals(HttpStatusCode.OK, courses.status, courses.bodyAsText())
        assertEquals(listOf("r1"), courses.body<LibraryDeleteResponse>().touchedRecipeIds, "only the recipe that had a course is touched")
        assertEquals(0, runBlocking { LibraryRepository.countCourses(userId) })
        assertNull(runBlocking { RecipeRepository.getById(userId, "r1")!! }.courseId)

        // Tags: the junction is cleared and the recipe restamped, exactly as for categories.
        val tags = client.post("/api/tags/delete") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryDeleteRequest(listOf("t-a")))
        }
        assertEquals(HttpStatusCode.OK, tags.status, tags.bodyAsText())
        assertEquals(listOf("r3"), tags.body<LibraryDeleteResponse>().touchedRecipeIds)
        r3 = runBlocking { RecipeRepository.getById(userId, "r3")!! }
        assertEquals(emptyList(), r3.tagIds)
        assertTrue(r3.lastModifiedDate!! > before)
        assertEquals(0, runBlocking { LibraryRepository.countTags(userId) })
    }

    /** Another account's rows cannot be deleted by naming them, and a malformed id is refused outright. */
    @Test
    fun bulkDeleteStaysInsideTheCallersLibrary() = testApplication {
        application { installSalty(imageStore) }
        val userId = testerId()
        val bobId = runBlocking { UserRepository.create("bob", "pw").id }
        runBlocking {
            LibraryRepository.upsertTag(userId, ServerTag("t-mine", "Quick"))
            LibraryRepository.upsertTag(bobId, ServerTag("t-bob", "Quick"))
            RecipeRepository.upsert(bobId, recipe("r-bob", "Bob's", "2026-02-01T00:00:00.000Z").copy(tagIds = listOf("t-bob")))
        }
        val client = jsonClient()
        val token = login(client)

        val resp = client.post("/api/tags/delete") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryDeleteRequest(listOf("t-mine", "t-bob")))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(listOf("t-mine"), resp.body<LibraryDeleteResponse>().removedIds)
        assertEquals(listOf("t-bob"), runBlocking { LibraryRepository.listTags(bobId) }.map { it.id })
        val bobs = runBlocking { RecipeRepository.getById(bobId, "r-bob")!! }
        assertEquals(listOf("t-bob"), bobs.tagIds)
        assertEquals("2026-02-01T00:00:00.000Z", bobs.lastModifiedDate)

        val bad = client.post("/api/tags/delete") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryDeleteRequest(listOf("../etc")))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

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

    /**
     * The CSRF token the app shell hands its own JSON calls, from the page's data attribute.
     */
    private suspend fun appCsrf(client: HttpClient): String =
        Regex("""data-csrf="([0-9a-f]+)"""").find(client.get("/app").bodyAsText())?.groupValues?.get(1)
            ?: error("no CSRF token in the app shell")

    /** A browser's shopping-list writes are session-cookie writes: no CSRF header, no write. */
    @Test
    fun shoppingListWritesFromTheBrowserNeedCsrf() = testApplication {
        application { installSalty(imageStore) }
        val uid = testerId()
        runBlocking {
            ShoppingListRepository.save(uid, ServerShoppingList(
                id = "sl", name = "Guarded", isFreeform = false,
                contentsForList = listOf(ShoppingListListContents(id = "i1", text = "Milk")),
                lastModifiedDate = "2026-08-01T00:00:00.000Z"))
        }
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val emptied = ServerShoppingList(
            id = "sl", name = "Guarded", isFreeform = false, contentsForList = emptyList(),
            lastModifiedDate = "2026-08-02T00:00:00.000Z", baseRevision = 1)
        val put = web.put("/api/shoppingLists/sl") { contentType(ContentType.Application.Json); setBody(emptied) }
        assertEquals(HttpStatusCode.Forbidden, put.status)
        assertEquals(HttpStatusCode.Forbidden, web.delete("/api/shoppingLists/sl").status)
        assertEquals(1, runBlocking { ShoppingListRepository.getById(uid, "sl")?.contentsForList?.size },
            "refused writes change nothing")

        val allowed = web.put("/api/shoppingLists/sl") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, appCsrf(web)); setBody(emptied)
        }
        assertEquals(HttpStatusCode.OK, allowed.status, allowed.bodyAsText())
        assertEquals(0, runBlocking { ShoppingListRepository.getById(uid, "sl")?.contentsForList?.size })
    }

    /** A signed-in browser never sees or deletes another account's list; an unknown id is a 404, not a 500. */
    @Test
    fun shoppingListsAreScopedToTheSignedInUser() = testApplication {
        application { installSalty(imageStore) }
        val otherId = runBlocking {
            UserRepository.create("other", "pw2")
            UserRepository.findByUsername("other")!!.id.also {
                ShoppingListRepository.save(it, ServerShoppingList(
                    id = "secret", name = "Other Persons List", lastModifiedDate = "2026-07-20T00:00:00.000Z"))
            }
        }
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        assertTrue(web.get("/api/shoppingLists").body<List<ServerShoppingList>>().none { it.id == "secret" })
        assertEquals(HttpStatusCode.NotFound, web.get("/api/shoppingLists/secret").status)
        assertEquals(HttpStatusCode.NotFound,
            web.delete("/api/shoppingLists/secret") { header(CSRF_HEADER, appCsrf(web)) }.status)
        assertEquals("Other Persons List", runBlocking { ShoppingListRepository.getById(otherId, "secret")?.name })
    }

    /** Reordering is a whole-list save: the new order is stored exactly, and a stale base conflicts. */
    @Test
    fun checklistReorderRoundTripsThroughTheApi() = testApplication {
        application { installSalty(imageStore) }
        val uid = testerId()
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
        val web = jsonCookieClient()
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        val csrf = appCsrf(web)
        val loaded = web.get("/api/shoppingLists/ord").body<ServerShoppingList>()
        val items = loaded.contentsForList!!
        val reordered = loaded.copy(
            contentsForList = listOf(items[2], items[0], items[1]),
            lastModifiedDate = "2026-08-02T00:00:00.000Z", baseRevision = loaded.revision, revision = null)

        val saved = web.put("/api/shoppingLists/ord") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf); setBody(reordered)
        }.body<ServerShoppingList>()
        assertEquals(listOf("Gamma", "Alpha", "Beta"), saved.contentsForList!!.map { it.text })
        assertEquals(loaded.revision!! + 1, saved.revision)
        assertEquals(listOf("Gamma", "Alpha", "Beta"),
            runBlocking { ShoppingListRepository.getById(uid, "ord")!!.contentsForList!!.map { it.text } })

        // The same edit sent again from the old base is someone else's view of the list now: 409.
        val stale = web.put("/api/shoppingLists/ord") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf); setBody(reordered)
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
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
