package com.enuvro.saltykmp.web

import com.enuvro.saltykmp.BuildInfo
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.auth.AccountLockout
import com.enuvro.saltykmp.auth.LoginThrottle
import com.enuvro.saltykmp.auth.MIN_PASSWORD_LENGTH
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.UserRow
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.image.ImageStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import java.security.SecureRandom

@Serializable
data class UserSession(
    val userId: String,
    val username: String,
    val isAdmin: Boolean = false,
    /** Anti-CSRF token minted at login and echoed as a hidden field in every state-changing form. */
    val csrfToken: String = "",
)

const val WEB_AUTH = "web-session"

private val csrfRandom = SecureRandom()

/** A fresh, unguessable CSRF token (kept in the MAC-signed session cookie, so it can't be forged). */
private fun newCsrfToken(): String {
    val bytes = ByteArray(32)
    csrfRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * View-only, server-rendered web UI (session-cookie auth). Edit forms can be added later as POSTs.
 *
 * The HTML lives in Mustache templates under `resources/templates/`; the handlers below build plain
 * view-model maps (all formatting/conditionals resolved here, since Mustache is logic-less). Shared
 * styles live in `resources/static/salty.css`, served at `/static/salty.css`.
 */
fun Route.webRoutes(imageStore: ImageStore, throttle: LoginThrottle, accountLockout: AccountLockout) {
    get("/login") {
        val error = call.request.queryParameters["error"] != null
        call.respond(MustacheContent("login.mustache", loginModel(error)))
    }
    post("/login") {
        val params = call.receiveParameters()
        val username = params["username"].orEmpty()
        val ip = call.request.origin.remoteHost
        // Per-IP throttle (single hammering source) + per-username lockout (slow distributed attack), both
        // checked before the bcrypt verify.
        if (throttle.retryAfterSeconds(ip, username) != null || accountLockout.retryAfterSeconds(username) != null) {
            call.respondRedirect("/login?error=1")
            return@post
        }
        val user = UserRepository.findByUsername(username)
        // Constant-time credential check (dummy bcrypt when the user is absent) to avoid username enumeration
        // via timing; the `&& user != null` gives a non-null smart-cast in the success branch.
        if (UserRepository.verifyCredential(user, params["password"].orEmpty()) && user != null) {
            throttle.recordSuccess(ip, username)
            accountLockout.recordSuccess(username)
            // Mint a fresh CSRF token per session and store it in the (signed) session cookie.
            call.sessions.set(UserSession(user.id, user.username, user.isAdmin, newCsrfToken()))
            call.respondRedirect("/")
        } else {
            throttle.recordFailure(ip, username)
            accountLockout.recordFailure(username)
            call.respondRedirect("/login?error=1")
        }
    }
    get("/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect("/login")
    }
    // Public: app name + build info. Reads the session cookie directly (route isn't behind
    // authenticate) so the account menu still reflects a logged-in visitor.
    get("/about") {
        call.respond(MustacheContent("about.mustache", aboutModel(call.sessions.get<UserSession>())))
    }

    authenticate(WEB_AUTH) {
        // Recipe Library — all recipes, searchable + paginated.
        get("/") {
            val session = call.principal<UserSession>()!!
            val query = call.request.queryParameters["q"].orEmpty().trim()
            val all = loadRecipesSorted(session.userId).search(query)
            val (pageItems, paging) = all.paginate(call)
            val courseNames = LibraryRepository.listCourses(session.userId).associate { it.id to it.name.orEmpty() }
            call.respond(MustacheContent("recipeList.mustache", recipeListModel(
                session, heading = "Recipe Library", recipes = pageItems, paging = paging,
                query = query, courseNames = courseNames, active = "library", basePath = "/",
            )))
        }

        // Browse-by index pages (Courses / Categories / Tags) with per-item recipe counts.
        get("/courses") {
            val session = call.principal<UserSession>()!!
            val all = loadRecipesSorted(session.userId)
            val items = LibraryRepository.listCourses(session.userId)
                .filter { !it.name.isNullOrBlank() }
                .map { BrowseItem(it.name!!, "/courses/${it.id}", all.count { r -> r.courseId == it.id }) }
            call.respond(MustacheContent("browseIndex.mustache", browseIndexModel(session, "Courses", "courses", items)))
        }
        get("/categories") {
            val session = call.principal<UserSession>()!!
            val all = loadRecipesSorted(session.userId)
            val items = LibraryRepository.listCategories(session.userId)
                .filter { !it.name.isNullOrBlank() }
                .map { c -> BrowseItem(c.name!!, "/categories/${c.id}", all.count { c.id in it.categoryIds.orEmpty() }) }
            call.respond(MustacheContent("browseIndex.mustache", browseIndexModel(session, "Categories", "categories", items)))
        }
        get("/tags") {
            val session = call.principal<UserSession>()!!
            val all = loadRecipesSorted(session.userId)
            val items = LibraryRepository.listTags(session.userId)
                .filter { !it.name.isNullOrBlank() }
                .map { t -> BrowseItem(t.name!!, "/tags/${t.id}", all.count { t.id in it.tagIds.orEmpty() }) }
            call.respond(MustacheContent("browseIndex.mustache", browseIndexModel(session, "Tags", "tags", items)))
        }

        // Filtered, paginated recipe lists (drill-down from a browse index).
        get("/courses/{id}") {
            val session = call.principal<UserSession>()!!
            val id = call.parameters["id"]!!
            val course = LibraryRepository.listCourses(session.userId).firstOrNull { it.id == id }
            if (course == null) { call.respondRedirect("/courses"); return@get }
            val query = call.request.queryParameters["q"].orEmpty().trim()
            val filtered = loadRecipesSorted(session.userId).filter { it.courseId == id }.search(query)
            val (pageItems, paging) = filtered.paginate(call)
            val courseNames = mapOf(id to course.name.orEmpty())
            call.respond(MustacheContent("recipeList.mustache", recipeListModel(
                session, heading = "Course: ${course.name.orEmpty()}", recipes = pageItems, paging = paging,
                query = query, courseNames = courseNames, active = "courses", basePath = "/courses/$id",
                backLink = "← All courses" to "/courses",
            )))
        }
        get("/categories/{id}") {
            val session = call.principal<UserSession>()!!
            val id = call.parameters["id"]!!
            val category = LibraryRepository.listCategories(session.userId).firstOrNull { it.id == id }
            if (category == null) { call.respondRedirect("/categories"); return@get }
            val query = call.request.queryParameters["q"].orEmpty().trim()
            val filtered = loadRecipesSorted(session.userId).filter { id in it.categoryIds.orEmpty() }.search(query)
            val (pageItems, paging) = filtered.paginate(call)
            val courseNames = LibraryRepository.listCourses(session.userId).associate { it.id to it.name.orEmpty() }
            call.respond(MustacheContent("recipeList.mustache", recipeListModel(
                session, heading = "Category: ${category.name.orEmpty()}", recipes = pageItems, paging = paging,
                query = query, courseNames = courseNames, active = "categories", basePath = "/categories/$id",
                backLink = "← All categories" to "/categories",
            )))
        }
        get("/tags/{id}") {
            val session = call.principal<UserSession>()!!
            val id = call.parameters["id"]!!
            val tag = LibraryRepository.listTags(session.userId).firstOrNull { it.id == id }
            if (tag == null) { call.respondRedirect("/tags"); return@get }
            val query = call.request.queryParameters["q"].orEmpty().trim()
            val filtered = loadRecipesSorted(session.userId).filter { id in it.tagIds.orEmpty() }.search(query)
            val (pageItems, paging) = filtered.paginate(call)
            val courseNames = LibraryRepository.listCourses(session.userId).associate { it.id to it.name.orEmpty() }
            call.respond(MustacheContent("recipeList.mustache", recipeListModel(
                session, heading = "Tag: ${tag.name.orEmpty()}", recipes = pageItems, paging = paging,
                query = query, courseNames = courseNames, active = "tags", basePath = "/tags/$id",
                backLink = "← All tags" to "/tags",
            )))
        }
        get("/recipes/{id}") {
            val session = call.principal<UserSession>()!!
            val recipe = RecipeRepository.getById(session.userId, call.parameters["id"]!!)
            if (recipe == null) {
                call.respondRedirect("/")
            } else {
                val courseName = recipe.courseId
                    ?.let { cid -> LibraryRepository.listCourses(session.userId).firstOrNull { it.id == cid }?.name }
                    ?.takeIf { it.isNotBlank() }
                val catMap = LibraryRepository.listCategories(session.userId).associate { it.id to it.name.orEmpty() }
                val tagMap = LibraryRepository.listTags(session.userId).associate { it.id to it.name.orEmpty() }
                val categoryNames = recipe.categoryIds.orEmpty().mapNotNull { catMap[it]?.takeIf(String::isNotBlank) }
                val tagNames = recipe.tagIds.orEmpty().mapNotNull { tagMap[it]?.takeIf(String::isNotBlank) }
                call.respond(MustacheContent("recipeDetail.mustache",
                    recipeDetailModel(session, recipe, courseName, categoryNames, tagNames)))
            }
        }
        get("/recipes/{id}/image") {
            val session = call.principal<UserSession>()!!
            val filename = RecipeRepository.imageFilename(session.userId, call.parameters["id"]!!)
            val bytes = filename?.let { imageStore.load(it) }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                val ct = when (filename.substringAfterLast('.', "").lowercase()) {
                    "png" -> ContentType.Image.PNG
                    "gif" -> ContentType.Image.GIF
                    else -> ContentType.Image.JPEG
                }
                call.respondBytes(bytes, ct)
            }
        }
        get("/recipes/{id}/thumbnail") {
            val session = call.principal<UserSession>()!!
            val filename = RecipeRepository.imageFilename(session.userId, call.parameters["id"]!!)
            val bytes = filename?.let { imageStore.loadThumbnail(it) }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
        }

        // ---- User management (admin only). Each user has an isolated set of recipes/library. ----
        get("/users") {
            val session = call.requireAdmin() ?: return@get
            val users = UserRepository.listAll()
            val notice = call.request.queryParameters["msg"]?.let(::userNotice)
            val error = call.request.queryParameters["error"]?.let(::userError)
            call.respond(MustacheContent("users.mustache", usersModel(session, users, notice, error)))
        }
        get("/users/new") {
            val session = call.requireAdmin() ?: return@get
            val error = call.request.queryParameters["error"]?.let(::userError)
            call.respond(MustacheContent("userForm.mustache",
                userFormModel(session, error, prefillUsername = call.request.queryParameters["u"].orEmpty())))
        }
        post("/users") {
            call.requireAdmin() ?: return@post
            val params = call.receiveParameters()
            if (!call.checkCsrf(params)) return@post
            val username = params["username"].orEmpty().trim()
            val password = params["password"].orEmpty()
            val makeAdmin = params["isAdmin"] == "on"
            when {
                username.isEmpty() || password.isEmpty() -> call.respondRedirect("/users/new?error=missing")
                password.length < MIN_PASSWORD_LENGTH -> call.respondRedirect("/users/new?error=weak&u=$username")
                UserRepository.existsByUsername(username) -> call.respondRedirect("/users/new?error=exists&u=$username")
                else -> {
                    UserRepository.create(username, password, makeAdmin)
                    call.respondRedirect("/users?msg=created")
                }
            }
        }
        post("/users/{id}/password") {
            call.requireAdmin() ?: return@post
            val params = call.receiveParameters()
            if (!call.checkCsrf(params)) return@post
            val id = call.parameters["id"]!!
            val password = params["password"].orEmpty()
            when {
                UserRepository.findById(id) == null -> call.respondRedirect("/users?error=notfound")
                password.length < MIN_PASSWORD_LENGTH -> call.respondRedirect("/users?error=weak")
                else -> {
                    UserRepository.changePassword(id, password)
                    call.respondRedirect("/users?msg=password")
                }
            }
        }
        post("/users/{id}/admin") {
            call.requireAdmin() ?: return@post
            val params = call.receiveParameters()
            if (!call.checkCsrf(params)) return@post
            val id = call.parameters["id"]!!
            val makeAdmin = params["isAdmin"] == "on"
            val target = UserRepository.findById(id)
            when {
                target == null -> call.respondRedirect("/users?error=notfound")
                // Don't let the last admin (often yourself) drop admin and lock everyone out.
                !makeAdmin && target.isAdmin && UserRepository.adminCount() <= 1 ->
                    call.respondRedirect("/users?error=lastadmin")
                else -> {
                    UserRepository.setAdmin(id, makeAdmin)
                    call.respondRedirect("/users?msg=updated")
                }
            }
        }
        post("/users/{id}/delete") {
            val session = call.requireAdmin() ?: return@post
            val params = call.receiveParameters()
            if (!call.checkCsrf(params)) return@post
            val id = call.parameters["id"]!!
            val target = UserRepository.findById(id)
            when {
                target == null -> call.respondRedirect("/users?error=notfound")
                target.id == session.userId -> call.respondRedirect("/users?error=self")
                target.isAdmin && UserRepository.adminCount() <= 1 -> call.respondRedirect("/users?error=lastadmin")
                else -> {
                    val images = UserRepository.deleteWithData(id)
                    images.forEach { imageStore.delete(it) }
                    call.respondRedirect("/users?msg=deleted")
                }
            }
        }
    }
}

/** Returns the admin session, or responds 403 and returns null (callers `?: return@…`). */
private suspend fun io.ktor.server.application.ApplicationCall.requireAdmin(): UserSession? {
    val session = principal<UserSession>()!!
    if (!session.isAdmin) {
        respond(HttpStatusCode.Forbidden, "Forbidden — administrator access required.")
        return null
    }
    return session
}

/**
 * Validates the CSRF token on a state-changing form POST: the hidden `csrf` field must match the token in
 * the session. Responds 403 and returns false on mismatch (callers `if (!checkCsrf(params)) return@post`).
 * Belt-and-suspenders with the session cookie's SameSite=Strict attribute.
 */
private suspend fun io.ktor.server.application.ApplicationCall.checkCsrf(params: Parameters): Boolean {
    val expected = principal<UserSession>()?.csrfToken.orEmpty()
    val provided = params["csrf"].orEmpty()
    if (expected.isEmpty() || provided != expected) {
        respond(HttpStatusCode.Forbidden, "Invalid or missing CSRF token.")
        return false
    }
    return true
}

private fun userNotice(code: String): String? = when (code) {
    "created" -> "User created."
    "deleted" -> "User and all their recipes deleted."
    "password" -> "Password updated."
    "updated" -> "User updated."
    else -> null
}

private fun userError(code: String): String? = when (code) {
    "missing" -> "Username and password are required."
    "weak" -> "Password must be at least $MIN_PASSWORD_LENGTH characters."
    "exists" -> "That username already exists."
    "notfound" -> "User not found."
    "self" -> "You can't delete the account you're signed in as."
    "lastadmin" -> "You can't remove the last administrator."
    else -> null
}

// ---- Browse / pagination helpers ----

/** Default page size when `?pageSize=` is absent. */
private const val PAGE_SIZE = 25

/** Upper bound for a caller-supplied `?pageSize=` — keeps pagination math safe and avoids huge pages. */
private const val MAX_PAGE_SIZE = 500

private data class Paging(val page: Int, val pageSize: Int, val total: Int) {
    val totalPages: Int get() = if (total == 0) 1 else (total + pageSize - 1) / pageSize
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page < totalPages
    val from: Int get() = if (total == 0) 0 else (page - 1) * pageSize + 1
    val to: Int get() = minOf(page * pageSize, total)
}

private data class BrowseItem(val name: String, val href: String, val count: Int)

/** The whole user's library, name-sorted. Recipe libraries are personal-scale, so an in-memory filter +
 * paginate keeps the query/browse code simple; swap for SQL paging if a library ever grows huge. */
private suspend fun loadRecipesSorted(userId: String): List<ServerRecipe> =
    RecipeRepository.listForSync(userId, null, null, 10_000).recipes.sortedBy { it.name.lowercase() }

private fun List<ServerRecipe>.search(query: String): List<ServerRecipe> =
    if (query.isBlank()) this else filter { it.name.contains(query, ignoreCase = true) }

/** Paginate using the request's `page` / `pageSize` query params (e.g. `?page=2&pageSize=50`). `pageSize`
 *  defaults to [PAGE_SIZE] and is bounded to [MAX_PAGE_SIZE]; `page` is clamped to the valid range. */
private fun List<ServerRecipe>.paginate(call: io.ktor.server.application.ApplicationCall): Pair<List<ServerRecipe>, Paging> {
    val requestedPage = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: PAGE_SIZE
    val total = size
    val totalPages = if (total == 0) 1 else (total + pageSize - 1) / pageSize
    val page = requestedPage.coerceIn(1, totalPages)
    val start = (page - 1) * pageSize
    val items = if (start >= total) emptyList() else subList(start, minOf(start + pageSize, total)).toList()
    return items to Paging(page, pageSize, total)
}

/** Builds a page link, carrying the active search and a non-default page size so they survive navigation. */
private fun pageUrl(basePath: String, page: Int, pageSize: Int, query: String): String {
    val size = if (pageSize == PAGE_SIZE) "" else "&pageSize=$pageSize"
    val q = if (query.isBlank()) "" else "&q=" + java.net.URLEncoder.encode(query, "UTF-8")
    return "$basePath?page=$page$size$q"
}

// ---- View models ----
//
// Mustache is logic-less, so every conditional, loop value, and formatted string is resolved here into
// plain Maps/Lists. Optional scalar fields are stored as `null` when absent so `{{#field}}…{{/field}}`
// (a presence section) skips them — empty/blank strings are normalised to null for the same reason.

/** Shared page chrome consumed by `head`/`nav`/`sidebar` partials. [sidebarActive] is the active
 *  sidebar key (or null for pages without a sidebar). */
private fun chrome(pageTitle: String, session: UserSession?, sidebarActive: String? = null): MutableMap<String, Any?> {
    val model = mutableMapOf<String, Any?>(
        "pageTitle" to pageTitle,
        "loggedIn" to (session != null),
        "username" to session?.username,
        "isAdmin" to (session?.isAdmin == true),
    )
    if (sidebarActive != null) {
        model["showHamburger"] = true
        model["sidebarItems"] = listOf(
            sidebarItem("Recipe Library", "/", "library", sidebarActive),
            sidebarItem("Courses", "/courses", "courses", sidebarActive),
            sidebarItem("Categories", "/categories", "categories", sidebarActive),
            sidebarItem("Tags", "/tags", "tags", sidebarActive),
        )
    }
    return model
}

private fun sidebarItem(label: String, href: String, key: String, active: String?) =
    mapOf("label" to label, "href" to href, "active" to (key == active))

private fun meta(label: String, value: String) = mapOf("label" to label, "value" to value)

private fun badge(cssClass: String, text: String) = mapOf("cssClass" to cssClass, "text" to text)

private fun loginModel(error: Boolean): Map<String, Any?> =
    chrome("Sign in", session = null).apply { put("error", error) }

private fun aboutModel(session: UserSession?): Map<String, Any?> =
    chrome("About", session).apply {
        put("appName", "Salty Server")
        put("version", BuildInfo.version)
        put("buildTime", BuildInfo.buildTime)
    }

private fun usersModel(session: UserSession, users: List<UserRow>, notice: String?, error: String?): Map<String, Any?> =
    chrome("Users", session).apply {
        put("notice", notice)
        put("error", error)
        put("csrfToken", session.csrfToken)
        put("users", users.map { u ->
            mapOf(
                "id" to u.id,
                "username" to u.username,
                "isAdmin" to u.isAdmin,
                "isSelf" to (u.id == session.userId),
            )
        })
    }

private fun userFormModel(session: UserSession, error: String?, prefillUsername: String): Map<String, Any?> =
    chrome("Add user", session).apply {
        put("error", error)
        put("csrfToken", session.csrfToken)
        put("prefillUsername", prefillUsername)
    }

private fun browseIndexModel(session: UserSession, heading: String, active: String, items: List<BrowseItem>): Map<String, Any?> =
    chrome(heading, session, sidebarActive = active).apply {
        put("heading", heading)
        put("isEmpty", items.isEmpty())
        put("emptyMessage", "No ${heading.lowercase()} yet.")
        put("items", items.map { mapOf("name" to it.name, "href" to it.href, "count" to it.count) })
    }

private fun recipeListModel(
    session: UserSession,
    heading: String,
    recipes: List<ServerRecipe>,
    paging: Paging,
    query: String,
    courseNames: Map<String, String>,
    active: String,
    basePath: String,
    backLink: Pair<String, String>? = null,
): Map<String, Any?> = chrome(heading, session, sidebarActive = active).apply {
    put("heading", heading)
    put("basePath", basePath)
    put("query", query)
    put("hasQuery", query.isNotBlank())
    put("nonDefaultPageSize", paging.pageSize != PAGE_SIZE)
    put("pageSize", paging.pageSize.toString())
    put("backLink", backLink?.let { (text, href) -> mapOf("text" to text, "href" to href) })
    put("isEmpty", recipes.isEmpty())
    if (recipes.isEmpty()) {
        put("emptyMessage", if (query.isNotBlank()) "No matching recipes." else "No recipes here yet.")
    } else {
        put("resultCount", "${paging.total} recipe${if (paging.total == 1) "" else "s"} · showing ${paging.from}–${paging.to}")
        put("recipes", recipes.map { recipeCardModel(it, courseNames) })
        put("pagination", paginationModel(paging, basePath, query))
    }
}

private fun recipeCardModel(r: ServerRecipe, courseNames: Map<String, String>): Map<String, Any?> {
    val metaInline = buildList {
        courseNames[r.courseId]?.takeIf { it.isNotBlank() }?.let { add(meta("Course", it)) }
        difficultyName(r.difficulty)?.let { add(meta("Difficulty", it)) }
        ratingName(r.rating)?.let { add(meta("Rating", it)) }
        r.servings?.let { add(meta("Servings", it.toString())) }
    }
    val badges = buildList {
        if (r.isFavorite == true) add(badge("favorite", "★ Favorite"))
        if (r.wantToMake == true) add(badge("want", "Want to Make"))
    }
    return mapOf(
        "id" to r.id,
        "name" to r.name,
        "hasImage" to (r.imageFilename != null),
        "summary" to r.introduction?.takeIf { it.isNotBlank() }?.snippet(150),
        "metaInline" to metaInline,
        "badges" to badges,
    )
}

private fun paginationModel(paging: Paging, basePath: String, query: String): Map<String, Any?>? {
    if (paging.totalPages <= 1) return null
    return mapOf(
        "page" to paging.page,
        "totalPages" to paging.totalPages,
        "hasPrev" to paging.hasPrev,
        "hasNext" to paging.hasNext,
        "prevUrl" to pageUrl(basePath, paging.page - 1, paging.pageSize, query),
        "nextUrl" to pageUrl(basePath, paging.page + 1, paging.pageSize, query),
    )
}

private fun recipeDetailModel(
    session: UserSession,
    r: ServerRecipe,
    courseName: String?,
    categoryNames: List<String>,
    tagNames: List<String>,
): Map<String, Any?> = chrome(r.name, session).apply {
    put("id", r.id)
    put("name", r.name)
    put("hasImage", r.imageFilename != null)

    put("metaBox", buildList {
        courseName?.let { add(meta("Course", it)) }
        difficultyName(r.difficulty)?.let { add(meta("Difficulty", it)) }
        ratingName(r.rating)?.let { add(meta("Rating", it)) }
        r.servings?.let { add(meta("Servings", it.toString())) }
        r.yield?.takeIf { it.isNotBlank() }?.let { add(meta("Yield", it)) }
    })
    put("badges", buildList {
        if (r.isFavorite == true) add(badge("favorite", "★ Favorite"))
        if (r.wantToMake == true) add(badge("want", "Want to Make"))
    })

    put("introduction", r.introduction?.takeIf { it.isNotBlank() })

    val prepTimes = r.preparationTimes.orEmpty().map { meta(it.type, it.timeString) }
    put("hasPrepTimes", prepTimes.isNotEmpty())
    put("prepTimes", prepTimes)

    val ingredients = r.ingredients.orEmpty().map { mapOf("text" to it.text, "heading" to it.isHeading) }
    put("hasIngredients", ingredients.isNotEmpty())
    put("ingredients", ingredients)

    val directions = r.directions.orEmpty().map { mapOf("text" to it.text, "heading" to (it.isHeading == true)) }
    put("hasDirections", directions.isNotEmpty())
    put("directions", directions)

    val notes = r.notes.orEmpty().map { mapOf("title" to it.title.takeIf(String::isNotBlank), "content" to it.content) }
    put("hasNotes", notes.isNotEmpty())
    put("notes", notes)

    val variations = r.variations.orEmpty().map { mapOf("title" to it.variationName.takeIf(String::isNotBlank), "text" to it.text) }
    put("hasVariations", variations.isNotEmpty())
    put("variations", variations)

    // Mirror the original: show the Nutrition section whenever a nutrition record exists.
    put("hasNutrition", r.nutrition != null)
    put("nutrition", r.nutrition?.let { nutritionCells(it) } ?: emptyList<Map<String, String>>())

    put("hasCategoriesOrTags", categoryNames.isNotEmpty() || tagNames.isNotEmpty())
    put("categories", categoryNames)
    put("tags", tagNames)

    put("hasSource", !r.source.isNullOrBlank() || !r.sourceDetails.isNullOrBlank())
    put("source", r.source?.takeIf { it.isNotBlank() })
    put("sourceDetails", r.sourceDetails?.takeIf { it.isNotBlank() })
}

private fun nutritionCells(n: NutritionInformation): List<Map<String, String>> = buildList {
    fun cell(label: String, value: Double?, suffix: String = "") {
        value?.let { add(mapOf("label" to label, "value" to formatNum(it) + suffix)) }
    }
    cell("Calories", n.calories)
    cell("Protein", n.protein, "g")
    cell("Carbs", n.carbohydrates, "g")
    cell("Fat", n.fat, "g")
    cell("Fiber", n.fiber, "g")
    cell("Sugar", n.sugar, "g")
    cell("Sodium", n.sodium, "mg")
    cell("Cholesterol", n.cholesterol, "mg")
}

private fun difficultyName(v: Int?): String? = when (v) {
    1 -> "Easy"
    2 -> "Somewhat Easy"
    3 -> "Medium"
    4 -> "Slightly Difficult"
    5 -> "Difficult"
    else -> null
}

private fun ratingName(v: Int?): String? = if (v != null && v in 1..5) "★".repeat(v) else null

private fun formatNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private fun String.snippet(max: Int): String = if (length <= max) this else take(max).trimEnd() + "…"
