package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.auth.CSRF_HEADER
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingListRepository
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.shoppinglist.ResolveResponse
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conflict resolution for the web editor, which has no local snapshot to merge with.
 *
 * The point of these is that a shopping list is NOT overwritten wholesale when two people touch
 * it — one checking an item off must not discard the other's edit to a different item. That
 * behaviour lives in the shared ShoppingListMerge; what's covered here is that the endpoint feeds
 * it the right three sides and persists what comes back.
 */
class ShoppingListResolveTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-resolve-img"))

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:saltyresolve;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }
        const val LIST_ID = "01A05400-0000-7000-8000-0000000000L1"
    }

    private fun item(id: String, text: String, completed: Boolean = false) =
        ShoppingListListContents(id = id, text = text, isCompleted = completed)

    @BeforeTest
    fun setUp() {
        ensureDb()
        runBlocking {
            DatabaseFactory.dbQuery {
                RecipeTags.deleteAll(); RecipeCategories.deleteAll()
                Recipes.deleteAll(); Courses.deleteAll(); Categories.deleteAll(); Tags.deleteAll()
                ShoppingLists.deleteAll(); DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            UserRepository.create("tester", "pw")
        }
    }

    private suspend fun uid() = UserRepository.findByUsername("tester")!!.id

    /** Seeds the shared starting point both sides will diverge from. */
    private suspend fun seedBase(): ServerShoppingList {
        val base = ServerShoppingList(
            id = LIST_ID,
            name = "Groceries",
            isFreeform = false,
            contentsForList = listOf(item("a", "Milk"), item("b", "Eggs"), item("c", "Bread")),
            lastModifiedDate = "2026-08-01T10:00:00.000Z",
        )
        return when (val r = ShoppingListRepository.save(uid(), base)) {
            is ShoppingListRepository.SaveResult.Saved -> r.list
            is ShoppingListRepository.SaveResult.Conflict -> error("seed conflicted")
        }
    }

    private fun ApplicationTestBuilder.client() = createClient {
        install(ContentNegotiation) { json(appJson) }
        install(HttpCookies)
    }

    private suspend fun login(client: HttpClient): String {
        client.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        val html = client.get("/app").bodyAsText()
        return Regex("""data-csrf="([0-9a-f]+)"""").find(html)!!.groupValues[1]
    }

    private suspend fun resolve(
        client: HttpClient, csrf: String, base: ServerShoppingList?, local: ServerShoppingList,
    ): ResolveResponse {
        val resp = client.post("/api/shoppingLists/$LIST_ID/resolve") {
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, csrf)
            setBody(mapOf("base" to base, "local" to local))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "resolve should succeed")
        return resp.body()
    }

    /**
     * The case the whole design exists for: one client checks an item off while another edits a
     * different item. Neither may be lost.
     */
    @Test
    fun aCheckOffAndAnUnrelatedEditBothSurvive() = testApplication {
        application { installSalty(imageStore) }
        val base = runBlocking { seedBase() }

        // Someone else checks off "Milk" and it lands first.
        runBlocking {
            ShoppingListRepository.save(
                uid(),
                base.copy(
                    contentsForList = listOf(item("a", "Milk", completed = true), item("b", "Eggs"), item("c", "Bread")),
                    lastModifiedDate = "2026-08-01T11:00:00.000Z",
                    baseRevision = base.revision,
                ),
            )
        }

        // The browser, still holding the original, renames a DIFFERENT item.
        val web = client()
        val csrf = login(web)
        val mine = base.copy(
            contentsForList = listOf(item("a", "Milk"), item("b", "Free-range eggs"), item("c", "Bread")),
            lastModifiedDate = "2026-08-01T11:30:00.000Z",
        )
        val merged = resolve(web, csrf, base, mine).merged
        val items = merged.contentsForList.orEmpty().associateBy { it.id }

        assertEquals(true, items["a"]?.isCompleted, "the other client's check-off must survive")
        assertEquals("Free-range eggs", items["b"]?.text, "this client's edit must survive")
        assertEquals(3, items.size, "no item should have been dropped")
    }

    /** Adds on both sides are unions, not a last-writer-wins overwrite of the list. */
    @Test
    fun additionsFromBothSidesAreKept() = testApplication {
        application { installSalty(imageStore) }
        val base = runBlocking { seedBase() }

        runBlocking {
            ShoppingListRepository.save(
                uid(),
                base.copy(
                    contentsForList = base.contentsForList.orEmpty() + item("d", "Butter"),
                    lastModifiedDate = "2026-08-01T11:00:00.000Z",
                    baseRevision = base.revision,
                ),
            )
        }

        val web = client()
        val csrf = login(web)
        val mine = base.copy(
            contentsForList = base.contentsForList.orEmpty() + item("e", "Jam"),
            lastModifiedDate = "2026-08-01T11:30:00.000Z",
        )
        val texts = resolve(web, csrf, base, mine).merged.contentsForList.orEmpty().map { it.text }

        assertTrue(texts.contains("Butter"), "the other side's addition must survive: $texts")
        assertTrue(texts.contains("Jam"), "this side's addition must survive: $texts")
    }

    /** Freeform text has no sensible auto-merge, so the losing side is preserved as a new list. */
    @Test
    fun conflictingFreeformTextIsPreservedAsACopy() = testApplication {
        application { installSalty(imageStore) }
        val base = runBlocking {
            val l = ServerShoppingList(
                id = LIST_ID, name = "Notes", isFreeform = true,
                contentsForFreeform = "milk\neggs",
                lastModifiedDate = "2026-08-01T10:00:00.000Z",
            )
            (ShoppingListRepository.save(uid(), l) as ShoppingListRepository.SaveResult.Saved).list
        }

        runBlocking {
            ShoppingListRepository.save(
                uid(),
                base.copy(contentsForFreeform = "milk\neggs\nbutter",
                         lastModifiedDate = "2026-08-01T11:00:00.000Z", baseRevision = base.revision),
            )
        }

        val web = client()
        val csrf = login(web)
        val mine = base.copy(contentsForFreeform = "milk\neggs\njam",
                             lastModifiedDate = "2026-08-01T11:30:00.000Z")
        val result = resolve(web, csrf, base, mine)

        assertNotNull(result.conflictCopy, "an unmergeable freeform edit must be kept, not dropped")
        val all = listOfNotNull(result.merged.contentsForFreeform, result.conflictCopy?.contentsForFreeform)
        assertTrue(all.any { it.contains("jam") }, "this side's text must exist somewhere: $all")
        assertTrue(all.any { it.contains("butter") }, "the other side's text must exist somewhere: $all")

        // And the copy is a real, retrievable list rather than an in-memory artefact.
        val stored = runBlocking { ShoppingListRepository.getById(uid(), result.conflictCopy!!.id) }
        assertNotNull(stored, "the conflict copy should have been saved")
    }

    /** With no base, the merge degrades to two-way — a check-off still must not be lost. */
    @Test
    fun withoutABaseTheMergeStillKeepsCheckOffs() = testApplication {
        application { installSalty(imageStore) }
        val base = runBlocking { seedBase() }

        runBlocking {
            ShoppingListRepository.save(
                uid(),
                base.copy(
                    contentsForList = listOf(item("a", "Milk", completed = true), item("b", "Eggs"), item("c", "Bread")),
                    lastModifiedDate = "2026-08-01T11:00:00.000Z",
                    baseRevision = base.revision,
                ),
            )
        }

        val web = client()
        val csrf = login(web)
        val mine = base.copy(
            contentsForList = listOf(item("a", "Milk"), item("b", "Eggs"), item("c", "Bread")),
            lastModifiedDate = "2026-08-01T11:30:00.000Z",
        )
        val merged = resolve(web, csrf, null, mine).merged
        val milk = merged.contentsForList.orEmpty().first { it.id == "a" }
        assertEquals(true, milk.isCompleted, "completed flags OR together even without a base")
    }

    @Test
    fun resolvingAMissingListIs404() = testApplication {
        application { installSalty(imageStore) }
        val web = client()
        val csrf = login(web)
        val resp = web.post("/api/shoppingLists/$LIST_ID/resolve") {
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, csrf)
            setBody(mapOf("base" to null, "local" to ServerShoppingList(id = LIST_ID, name = "Ghost")))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    /** The endpoint is a write, so it must obey the same CSRF rule as every other cookie write. */
    @Test
    fun resolveRequiresACsrfToken() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { seedBase() }
        val web = client()
        login(web)
        val resp = web.post("/api/shoppingLists/$LIST_ID/resolve") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("base" to null, "local" to ServerShoppingList(id = LIST_ID, name = "Nope")))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        val stored = runBlocking { ShoppingListRepository.getById(uid(), LIST_ID) }
        assertEquals("Groceries", stored?.name, "a rejected resolve must not have written anything")
    }

    /** A clean merge leaves no conflict copy behind. */
    @Test
    fun aCleanMergeProducesNoCopy() = testApplication {
        application { installSalty(imageStore) }
        val base = runBlocking { seedBase() }
        val web = client()
        val csrf = login(web)
        val mine = base.copy(
            contentsForList = listOf(item("a", "Whole milk"), item("b", "Eggs"), item("c", "Bread")),
            lastModifiedDate = "2026-08-01T11:30:00.000Z",
        )
        assertNull(resolve(web, csrf, base, mine).conflictCopy)
    }
}
