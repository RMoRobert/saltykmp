package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.ServerRecipe
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
import com.enuvro.saltykmp.dev.DevSeed
import com.enuvro.saltykmp.image.ImageStore
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dev seeder exists for local UI work, but its GUARDS are what matter here: a checkout without
 * a seed directory must behave as though this code did not exist, and a library with real recipes
 * in it must never be touched. Both are easy to break and impossible to notice on the machine that
 * has the seed data.
 */
class DevSeedTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-seed-img"))

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:saltyseed;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }
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
            UserRepository.create("seeder", "pw")
        }
    }

    private fun uid() = runBlocking { UserRepository.findByUsername("seeder")!!.id }

    /** One recipe exercising the parts an importer gets wrong: names, headings, nested rows. */
    private fun sampleJson() = """
        [{
          "version": "1.0",
          "id": "39C47B8C-5070-441A-8B5A-F1F18DCD869B",
          "name": "Seeded Pie",
          "createdDate": "2025-08-07T02:21:53Z",
          "lastModifiedDate": "2026-07-25T04:07:00Z",
          "difficulty": 3,
          "rating": 4,
          "isFavorite": true,
          "wantToMake": false,
          "yield": "16",
          "servings": 8,
          "course": "Main",
          "categories": ["Dinner", "Baking"],
          "tags": ["party"],
          "ingredients": [
            { "text": "Filling", "isHeading": true },
            { "text": "500g beef", "isMain": true },
            { "text": "1 onion" }
          ],
          "directions": [
            { "text": "Prepare filling", "isHeading": true },
            { "text": "Brown the beef." }
          ],
          "preparationTimes": [{ "type": "Prep", "timeString": "30 min" }],
          "notes": [{ "id": "N1", "title": "Serving", "content": "Best warm." }],
          "variations": [{ "variationName": "Vegetarian", "text": "Use lentils." }],
          "nutrition": { "id": "NU1", "calories": 410, "protein": 18.5 }
        }]
    """.trimIndent()

    private fun seedDirWith(json: String): Path {
        val dir = Files.createTempDirectory("salty-seed")
        Files.writeString(dir.resolve("library.saltyrecipe"), json)
        return dir
    }

    /** The whole point: a checkout without the directory is completely unaffected. */
    @Test
    fun aMissingSeedDirectoryDoesNothing() = runBlocking {
        val absent = Files.createTempDirectory("salty-seed-parent").resolve("not-here")
        assertEquals(0, DevSeed.seedIfRequested(absent, imageStore, uid()))
        assertEquals(0L, RecipeRepository.count(uid()), "nothing should have been created")
    }

    /** Never overwrite a library someone is actually using. */
    @Test
    fun anExistingLibraryIsLeftAlone() = runBlocking {
        RecipeRepository.upsert(uid(), ServerRecipe(id = "KEEP-ME", name = "My Own Recipe"))
        val dir = seedDirWith(sampleJson())

        assertEquals(0, DevSeed.seedIfRequested(dir, imageStore, uid()), "should refuse to seed")
        assertEquals(1L, RecipeRepository.count(uid()))
        assertEquals("My Own Recipe", RecipeRepository.getById(uid(), "KEEP-ME")?.name)
    }

    /** Course, categories and tags arrive as names and have to become real library rows. */
    @Test
    fun seedingImportsRecipesAndResolvesLibraryNames() = runBlocking {
        val dir = seedDirWith(sampleJson())
        assertEquals(1, DevSeed.seedIfRequested(dir, imageStore, uid()))

        val r = RecipeRepository.getById(uid(), "39C47B8C-5070-441A-8B5A-F1F18DCD869B")!!
        assertEquals("Seeded Pie", r.name)
        assertEquals(true, r.isFavorite)
        assertEquals(8, r.servings)

        val course = LibraryRepository.listCourses(uid()).single()
        assertEquals("Main", course.name)
        assertEquals(course.id, r.courseId)
        assertEquals(setOf("Dinner", "Baking"), LibraryRepository.listCategories(uid()).map { it.name }.toSet())
        assertEquals(2, r.categoryIds?.size)
        assertEquals(listOf("party"), LibraryRepository.listTags(uid()).map { it.name })

        // Nested rows carry no ids in the file; every one must get a real one.
        val ids = r.ingredients.orEmpty().map { it.id } + r.directions.orEmpty().map { it.id } +
            r.preparationTimes.orEmpty().map { it.id } + r.variations.orEmpty().map { it.id }
        assertTrue(ids.isNotEmpty() && ids.none { it.isBlank() }, "every nested row needs an id")
        assertEquals(ids.size, ids.toSet().size, "minted ids must be unique")
        assertEquals(true, r.ingredients?.first()?.isHeading, "headings must survive")
        assertEquals(true, r.ingredients?.get(1)?.isMain, "main-ingredient flags must survive")
        assertEquals(1, r.variations?.size)
        assertEquals(1, r.preparationTimes?.size)
        assertEquals(410.0, r.nutrition?.calories)

        // Exports carry second-precision stamps; stored rows use the full wire shape.
        assertTrue(r.lastModifiedDate!!.matches(Regex(""".*\.\d{3}Z$""")),
            "date should be normalised to milliseconds, was ${r.lastModifiedDate}")
    }

    /** Classifier names that differ only by case must not create duplicate library rows. */
    @Test
    fun repeatedClassifierNamesAreReusedNotDuplicated() = runBlocking {
        val two = sampleJson().trimEnd().removeSuffix("]") + """
            ,{ "version": "1.0", "id": "SECOND", "name": "Another",
               "course": "main", "categories": ["baking"], "tags": ["PARTY"],
               "difficulty": 0, "rating": 0, "isFavorite": false, "wantToMake": false,
               "ingredients": [], "directions": [], "preparationTimes": [], "notes": [] }]
        """.trimIndent()
        val dir = seedDirWith(two)
        assertEquals(2, DevSeed.seedIfRequested(dir, imageStore, uid()))

        assertEquals(1, LibraryRepository.listCourses(uid()).size, "\"Main\" and \"main\" are one course")
        assertEquals(2, LibraryRepository.listCategories(uid()).size, "Dinner + Baking, not Baking twice")
        assertEquals(1, LibraryRepository.listTags(uid()).size, "\"party\" and \"PARTY\" are one tag")
    }

    /** A corrupt file must not take the server down on startup. */
    @Test
    fun anUnreadableFileIsSkippedRatherThanFatal() = runBlocking {
        val dir = seedDirWith("{ this is not json")
        assertEquals(0, DevSeed.seedIfRequested(dir, imageStore, uid()), "should survive bad input")
        assertEquals(0L, RecipeRepository.count(uid()))
    }

    /**
     * The seed directory is found relative to the working directory, and that differs by launcher:
     * `gradlew :server:run` runs in server/, an IDE run configuration for ApplicationKt usually
     * runs in the repo root. Looking in one place meant the seeder silently did nothing under the
     * other launcher -- indistinguishable from it being broken, since a missing directory is a
     * deliberate no-op.
     */
    @Test
    fun theSeedDirectoryIsFoundFromEitherWorkingDirectory() {
        assertEquals(listOf("seed", "server/seed", "../seed"), DevSeed.DEFAULT_DIRS,
            "both launchers' working directories must be covered")
    }

    /** An explicit SALTY_SEED_DIR overrides the search entirely, and is not second-guessed. */
    @Test
    fun anExplicitDirectoryWinsAndIsNotSearchedFor() {
        val dir = seedDirWith(sampleJson())
        assertEquals(dir, DevSeed.resolveDir(dir.toString()))
        assertEquals(null, DevSeed.resolveDir("/nowhere/that/exists"),
            "an explicit path that doesn't exist must not silently fall back to a candidate")
    }
}
