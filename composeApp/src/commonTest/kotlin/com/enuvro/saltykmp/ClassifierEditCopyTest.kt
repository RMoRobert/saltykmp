package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.LibraryClassifierItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two confirmations in the classifier editor are the only thing between the user and an
 * irreversible bulk edit, so their wording is tested rather than eyeballed. Mirrors the Swift app's
 * LibraryClassifierDeletionCopyTests / LibraryClassifierMergeCopyTests.
 */
class ClassifierEditCopyTest {

    private fun item(id: String, name: String, recipeCount: Int = 0) =
        LibraryClassifierItem(id, name, recipeCount)

    // ---- Deletion ----

    @Test
    fun oneRowIsNamedInTheDeleteTitleAndSeveralAreCounted() {
        assertEquals(
            "Delete \"Breads\"?",
            classifierDeletionTitle(listOf(item("1", "Breads")), ClassifierKind.Categories),
        )
        assertEquals(
            "Delete 3 Tags?",
            classifierDeletionTitle(List(3) { item("$it", "t$it") }, ClassifierKind.Tags),
        )
    }

    @Test
    fun anUnusedRowGetsTheShortConfirmationButStillGetsOne() {
        assertEquals(
            "This cannot be undone.",
            classifierDeletionMessage(listOf(item("1", "Orphan")), ClassifierKind.Tags),
        )
    }

    @Test
    fun aUsedCategorySaysWhatTheRecipesLoseAndThatTheySurvive() {
        val message = classifierDeletionMessage(
            listOf(item("1", "Breads", recipeCount = 12)),
            ClassifierKind.Categories,
        )
        assertEquals(
            "This category is being used by 12 recipes. Removing it will remove it from those recipes, " +
                "but the recipes will remain. This cannot be undone.",
            message,
        )
    }

    @Test
    fun severalCategoriesHedgeTheTotalBecauseOneRecipeCanHoldTwoOfThem() {
        val message = classifierDeletionMessage(
            listOf(item("1", "Breads", 12), item("2", "Baking", 5)),
            ClassifierKind.Categories,
        )
        assertTrue(message.startsWith("These categories are being used by up to 17 recipes."), message)
    }

    @Test
    fun severalCoursesDoNotHedgeBecauseARecipeHasOnlyOne() {
        val message = classifierDeletionMessage(
            listOf(item("1", "Dessert", 4), item("2", "Snack", 3)),
            ClassifierKind.Courses,
        )
        assertEquals(
            "7 recipes are currently classified with these courses. Those recipes will remain, but their " +
                "course selection will be removed. This cannot be undone.",
            message,
        )
    }

    @Test
    fun aSingleRecipeIsSaidInTheSingular() {
        val message = classifierDeletionMessage(listOf(item("1", "Dessert", 1)), ClassifierKind.Courses)
        assertEquals(
            "1 recipe is currently classified with this course. That recipe will remain, but its course " +
                "selection will be removed. This cannot be undone.",
            message,
        )
    }

    // ---- Merge ----

    @Test
    fun theMergeTitleCountsTheRowsBeingFolded() {
        assertEquals(
            "Merge 2 Categories",
            classifierMergeTitle(listOf(item("1", "Desserts"), item("2", "Deserts")), ClassifierKind.Categories),
        )
    }

    @Test
    fun withNoSurvivorChosenTheMessageAsksForOne() {
        assertEquals(
            "Choose which tag to keep.",
            classifierMergeMessage(listOf(item("1", "quick")), survivorId = null, kind = ClassifierKind.Tags),
        )
    }

    @Test
    fun theMessageNamesTheLosersAndTheSurvivorsSpelling() {
        val rows = listOf(item("1", "Desserts", 12), item("2", "Deserts", 3))
        assertEquals(
            "\"Deserts\" will be deleted, and its recipes will be added to \"Desserts\". The recipes " +
                "themselves are not deleted. This cannot be undone.",
            classifierMergeMessage(rows, survivorId = "1", kind = ClassifierKind.Categories),
        )
    }

    @Test
    fun aCourseMergeSaysTheRecipesSwitchRatherThanGainOne() {
        val rows = listOf(item("1", "Dessert", 9), item("2", "Desert", 1))
        assertTrue(
            classifierMergeMessage(rows, survivorId = "1", kind = ClassifierKind.Courses)
                .startsWith("\"Desert\" will be deleted, and its recipes will use \"Dessert\" instead."),
        )
    }

    @Test
    fun threeLosersAreListedAndFourAreCounted() {
        val survivor = item("0", "quick", 20)
        val three = listOf(survivor, item("1", "Quick"), item("2", "fast"), item("3", "speedy"))
        assertTrue(
            classifierMergeMessage(three, survivorId = "0", kind = ClassifierKind.Tags)
                .startsWith("\"Quick\", \"fast\", and \"speedy\" will be deleted, and their recipes"),
            classifierMergeMessage(three, survivorId = "0", kind = ClassifierKind.Tags),
        )

        val four = three + item("4", "rapid")
        assertTrue(
            classifierMergeMessage(four, survivorId = "0", kind = ClassifierKind.Tags)
                .startsWith("The other 4 tags will be deleted, and their recipes"),
            classifierMergeMessage(four, survivorId = "0", kind = ClassifierKind.Tags),
        )
    }

    @Test
    fun aSurvivorWithNothingToFoldSaysSo() {
        val rows = listOf(item("1", "quick"))
        assertEquals(
            "Nothing to merge into \"quick\".",
            classifierMergeMessage(rows, survivorId = "1", kind = ClassifierKind.Tags),
        )
    }
}
