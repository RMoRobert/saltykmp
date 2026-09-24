package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.model.Rating
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a screen reader hears for a recipe row: the name always, and first. The subtitles are real ones. */
class RecipeRowDescriptionTest {

    @Test
    fun aRatedFavoriteKeepsItsNameAndSaysBoth() {
        // The kind of row that used to be read as nothing but "Rated 5 of 5 stars, Favorite".
        assertEquals(
            "Banana-Oat Waffles, rated 5 of 5 stars, favorite, " +
                "This incredible gluten-free banana oat waffles recipe requires only one kind of flour—oat flour!",
            recipeRowDescription(
                "Banana-Oat Waffles",
                Rating.FIVE,
                isFavorite = true,
                "This incredible gluten-free banana oat waffles recipe requires only one kind of flour—oat " +
                    "flour! The waffles are delicious, easy to make, and wholesome, too.",
            ),
        )
    }

    @Test
    fun anUnratedRowSaysNothingAboutStars() {
        assertEquals(
            "Basil Pesto, Robert Morris and AllRecipes",
            recipeRowDescription("Basil Pesto", Rating.NOT_SET, isFavorite = false, "Robert Morris and AllRecipes"),
        )
    }

    @Test
    fun aBlankSubtitleLeavesNoTrailingComma() {
        assertEquals("Toast, rated 3 of 5 stars", recipeRowDescription("Toast", Rating.THREE, isFavorite = false, " "))
    }

    @Test
    fun anIntroductionIsCutToItsFirstSentence() {
        assertEquals(
            "Classic Aussie fare - a meat pie with a beef filling that's been braised until tender.",
            spokenSubtitle(
                "Classic Aussie fare - a meat pie with a beef filling that's been braised until tender. Great " +
                    "food for parties that freezes extremely well!",
            ),
        )
    }

    @Test
    fun aPageReferenceDoesNotEndTheSentence() {
        assertEquals(
            "If you have leftover White Beans, Tuscan Style (How to Cook Anything Vegetarian 2e, p. 444), these " +
                "work well here drained.",
            spokenSubtitle(
                "If you have leftover White Beans, Tuscan Style (How to Cook Anything Vegetarian 2e, p. 444), " +
                    "these work well here drained. If not: any cooked white beans, even canned, will work great!",
            ),
        )
    }

    @Test
    fun aRunOnSentenceEndsAtAClauseRatherThanMidPhrase() {
        assertEquals(
            "Inspired by the savory heat of pad krapow, the popular Thai dish of stir-fried ground chicken or " +
                "pork with chiles, garlic and basil",
            spokenSubtitle(
                "Inspired by the savory heat of pad krapow, the popular Thai dish of stir-fried ground chicken or " +
                    "pork with chiles, garlic and basil, this tofu version is just as robustly seasoned and satiating.",
            ),
        )
        assertEquals(
            "This quick, lighter version of the Mexican favorite is baked with a filling of cottage cheese and " +
                "black beans",
            spokenSubtitle(
                "This quick, lighter version of the Mexican favorite is baked with a filling of cottage cheese and " +
                    "black beans, then topped with a simple tomato sauce. The enchiladas would also be great.",
            ),
        )
    }

    @Test
    fun aRunOnSentenceWithoutClausesIsCutAtAWord() {
        val spoken = spokenSubtitle("word ".repeat(60).trim())
        assertEquals(true, spoken.length <= 140, spoken)
        assertEquals(true, spoken.endsWith("word"), spoken)
    }

    @Test
    fun aWebAddressIsSaidAsItsSite() {
        assertEquals("handletheheat.com", spokenSubtitle("http://www.handletheheat.com/baked-smores-doughnuts/"))
        assertEquals("example.org", spokenSubtitle("https://example.org"))
    }

    @Test
    fun shortSubtitlesAreSaidAsTheyAre() {
        assertEquals("AllRecipes.com", spokenSubtitle("AllRecipes.com"))
        assertEquals("Last prepared Sep 21, 2026", spokenSubtitle("Last prepared Sep 21, 2026"))
        assertEquals("Never prepared", spokenSubtitle("Never prepared"))
    }
}
