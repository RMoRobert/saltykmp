package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.sync.LocalStore
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The schema's `ON DELETE` clauses, against a FILE database — which is the only place they were ever
 * broken.
 *
 * `PRAGMA foreign_keys` is per connection, and for a file URL the JDBC driver opens a connection per
 * statement and closes it again whenever no transaction is open. Running the pragma as a statement
 * therefore set it on a connection that was immediately thrown away, and everything afterwards ran
 * with foreign keys OFF: deleting a course left recipes pointing at an id that no longer existed,
 * and `recipeForUpload` then sent that dangling id to the server.
 *
 * In-memory databases keep one connection for the life of the driver, so every other test in this
 * module passes either way. That is exactly why this one insists on a file.
 */
class ForeignKeyEnforcementTest {

    @Test
    fun deletingACourseClearsItFromRecipesInAFileDatabase() {
        val dir = Files.createTempDirectory("salty-fk")
        val db = createAppDatabase(dir.resolve("library.sqlite").absolutePathString())
        val local = LocalStore(db)

        local.upsertCourse(ServerCourse(id = "c1", name = "Breads"))
        local.upsertRecipe(
            ServerRecipe(
                id = "r1",
                name = "Cornbread",
                courseId = "c1",
                lastModifiedDate = "2026-01-01T00:00:00.000Z",
            ),
        )
        assertEquals("c1", local.recipeForUpload("r1")?.courseId, "the recipe starts out filed")

        local.deleteCourse("c1")

        assertNull(
            local.recipeForUpload("r1")?.courseId,
            "ON DELETE SET NULL has to fire, or the recipe points at a course that is gone",
        )
    }
}
