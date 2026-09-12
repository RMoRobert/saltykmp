package com.enuvro.saltykmp.importer

import com.enuvro.saltykmp.export.SaltyDirectionExport
import com.enuvro.saltykmp.export.SaltyIngredientExport
import com.enuvro.saltykmp.export.SaltyPreparationTimeExport
import com.enuvro.saltykmp.export.SaltyRecipeExport
import com.enuvro.saltykmp.export.SaltyVariationExport
import com.enuvro.saltykmp.db.model.Note
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the import: dates, photos, the row a document becomes, and what the user is told. */
class SaltyRecipeImportTest {

    private val now = "2026-09-11T10:00:00.000Z"

    // ---- Dates ----

    @Test
    fun swiftsWholeSecondDatesGainTheirMilliseconds() {
        assertEquals("2025-08-07T20:17:17.000Z", importedDate("2025-08-07T20:17:17Z"))
    }

    @Test
    fun aZonelessDateIsReadAsUtc() {
        // Seen in a real file: fractional seconds, no zone.
        assertEquals("2025-08-07T02:21:53.000Z", importedDate("2025-08-07T02:21:53.000"))
        assertEquals("2025-12-06T18:46:27.383Z", importedDate("2025-12-06T18:46:27.383"))
        // The database's own shape, should one ever travel.
        assertEquals("2025-08-07T02:21:53.000Z", importedDate("2025-08-07 02:21:53.000"))
    }

    @Test
    fun anOffsetIsFoldedIntoUtc() {
        assertEquals("2025-08-07T18:17:17.000Z", importedDate("2025-08-07T20:17:17+02:00"))
    }

    @Test
    fun anUnreadableDateIsDroppedRatherThanStored() {
        assertNull(importedDate("last Tuesday"))
        assertNull(importedDate(""))
        assertNull(importedDate(null))
    }

    // ---- Photos ----

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun imageDataDecodesWithOrWithoutPaddingAndLineBreaks() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x10, 0x20)
        val encoded = Base64.encode(bytes)
        assertContentEquals(bytes, decodeImageData(encoded))
        assertContentEquals(bytes, decodeImageData(encoded.trimEnd('=')))
        assertContentEquals(bytes, decodeImageData(encoded.chunked(2).joinToString("\r\n")))
    }

    @Test
    fun missingOrUndecodableImageDataIsNoPhoto() {
        assertNull(decodeImageData(null))
        assertNull(decodeImageData("  "))
        assertNull(decodeImageData("not base64 at all!"))
    }

    @Test
    fun thePhotosExtensionComesFromItsBytes() {
        assertEquals("jpg", imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("png", imageExtension(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("heic", imageExtension("....ftypheic....".encodeToByteArray()))
        assertEquals("gif", imageExtension("GIF89a".encodeToByteArray()))
        assertEquals("webp", imageExtension("RIFF....WEBPVP8 ".encodeToByteArray()))
        assertEquals("jpg", imageExtension(byteArrayOf(1, 2, 3)), "unrecognised falls back to jpg, as in Swift")
    }

    // ---- The row a document becomes ----

    private val document = SaltyRecipeExport(
        id = "file-id",
        name = "  Focaccia  ",
        createdDate = "2025-08-07T20:17:17Z",
        lastModifiedDate = "2025-08-11T03:08:54Z",
        lastPrepared = "2025-09-01T12:00:00Z",
        source = "",
        introduction = "Crisp and airy.",
        difficulty = 2,
        rating = 9,
        isFavorite = true,
        yield = "1 tray",
        servings = 8,
        directions = listOf(SaltyDirectionExport("Dough", isHeading = true), SaltyDirectionExport("Knead.")),
        ingredients = listOf(
            SaltyIngredientExport("For the dough", isHeading = true, isMain = true),
            SaltyIngredientExport("500 g flour", isMain = true),
            SaltyIngredientExport("10 g salt"),
        ),
        notes = listOf(Note(id = "old-note-id", title = "Tip", content = "Hot oven.")),
        variations = listOf(SaltyVariationExport("Olive", "Add olives.")),
        preparationTimes = listOf(SaltyPreparationTimeExport("Rise", "2 hr")),
    )

    private fun newRecipe(doc: SaltyRecipeExport = document): com.enuvro.saltykmp.api.ServerRecipe {
        var n = 0
        return doc.toNewRecipe("new-id", "course-1", listOf("cat-1"), listOf("tag-1"), now, rowId = { "row-${++n}" })
    }

    @Test
    fun theRecipeAndEveryNestedRowGetNewIds() {
        val r = newRecipe()
        assertEquals("new-id", r.id)
        assertEquals(listOf("row-1", "row-2"), r.directions?.map { it.id })
        assertEquals(listOf("row-3", "row-4", "row-5"), r.ingredients?.map { it.id })
        assertEquals(listOf("row-6"), r.notes?.map { it.id }, "the file's note id is not reused")
        assertEquals(listOf("row-7"), r.variations?.map { it.id })
        assertEquals(listOf("row-8"), r.preparationTimes?.map { it.id })
    }

    @Test
    fun classificationIsWhatTheCallerResolved() {
        val r = newRecipe()
        assertEquals("course-1", r.courseId)
        assertEquals(listOf("cat-1"), r.categoryIds)
        assertEquals(listOf("tag-1"), r.tagIds)
    }

    @Test
    fun datesComeAcrossAndThePreparedStampIsNow() {
        val r = newRecipe()
        assertEquals("2025-08-07T20:17:17.000Z", r.createdDate)
        assertEquals("2025-08-11T03:08:54.000Z", r.lastModifiedDate)
        assertEquals("2025-09-01T12:00:00.000Z", r.lastPrepared)
        assertEquals(now, r.lastModifiedPreparedDate)
    }

    @Test
    fun missingDatesAreNowAndNoPreparedDateMeansNoStamp() {
        val r = newRecipe(document.copy(createdDate = null, lastModifiedDate = "garbage", lastPrepared = null))
        assertEquals(now, r.createdDate)
        assertEquals(now, r.lastModifiedDate)
        assertNull(r.lastPrepared)
        assertNull(r.lastModifiedPreparedDate)
    }

    @Test
    fun valuesAreTidiedTheWayTheEditorWouldStoreThem() {
        val r = newRecipe()
        assertEquals("Focaccia", r.name)
        assertNull(r.source, "blank becomes absent")
        assertEquals(2, r.difficulty)
        assertNull(r.rating, "out of range becomes not set")
        assertEquals(listOf(true, false, false), r.ingredients?.map { it.isHeading })
        assertEquals(listOf(false, true, false), r.ingredients?.map { it.isMain }, "a heading is never main")
        assertEquals(listOf(true, null), r.directions?.map { it.isHeading })
    }

    @Test
    fun aNamelessRecipeGetsAPlaceholderName() {
        assertEquals("Untitled Recipe", newRecipe(document.copy(name = "   ")).name)
    }

    @Test
    fun noVariationsIsAnEmptyList() {
        assertEquals(emptyList(), newRecipe(document.copy(variations = null)).variations)
    }

    // ---- What the user is told ----

    private fun recipes(vararg names: String) = names.mapIndexed { i, n -> ImportedRecipe("id$i", n) }

    @Test
    fun oneRecipeFromOneFileIsNamed() {
        val s = RecipeFileImportSummary(recipes("Focaccia"), fileCount = 1, failedFiles = emptyList(), skipped = 0)
        assertTrue(s.isClean)
        assertEquals("Imported \"Focaccia\"", s.message)
    }

    @Test
    fun severalRecipesAreCounted() {
        assertEquals(
            "Imported 3 recipes",
            RecipeFileImportSummary(recipes("a", "b", "c"), 1, emptyList(), 0).message,
        )
        assertEquals(
            "Imported 3 recipes from 2 files",
            RecipeFileImportSummary(recipes("a", "b", "c"), 2, emptyList(), 0).message,
        )
    }

    @Test
    fun shortfallsAreSpelledOut() {
        val s = RecipeFileImportSummary(recipes("a", "b"), fileCount = 3, failedFiles = listOf("notes.txt"), skipped = 1)
        assertEquals(false, s.isClean)
        assertEquals("Import Complete", s.title)
        assertEquals(
            "Imported 2 recipes from 3 files.\n\n1 recipe couldn't be read.\n\n" +
                "Couldn't read 1 file as Salty recipes: notes.txt.",
            s.message,
        )
    }

    @Test
    fun aLongListOfFailedFilesIsCut() {
        val failed = (1..7).map { "f$it.txt" }
        val s = RecipeFileImportSummary(emptyList(), fileCount = 7, failedFiles = failed, skipped = 0)
        assertEquals("Import Failed", s.title)
        assertEquals(
            "No recipes could be imported from the selected files.\n\n" +
                "Couldn't read 7 files as Salty recipes: f1.txt, f2.txt, f3.txt, f4.txt, f5.txt, and 2 more.",
            s.message,
        )
    }

    @Test
    fun nothingImportedFromOneFileSaysFile() {
        val s = RecipeFileImportSummary(emptyList(), fileCount = 1, failedFiles = listOf("x.saltyRecipe"), skipped = 0)
        assertTrue(s.message.startsWith("No recipes could be imported from the selected file."), s.message)
    }
}
