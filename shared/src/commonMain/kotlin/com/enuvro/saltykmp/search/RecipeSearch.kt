package com.enuvro.saltykmp.search

/**
 * Which recipe fields a search looks at. Mirrors the Swift app's `RecipeListSearchOptions` (Settings →
 * "Search Options" there, the search menu here) so the same query behaves the same way in both apps.
 */
enum class RecipeSearchField(val label: String) {
    NAME("Name"),
    INGREDIENTS("Ingredients"),
    INTRODUCTION("Introduction"),
    CATEGORY("Category"),
    COURSE("Course"),
    TAGS("Tags"),
    NOTES("Notes"),
    VARIATIONS("Variations"),
    ;

    companion object {
        /** What a fresh install searches: the name only, matching the Swift app's default. */
        val DEFAULTS: Set<RecipeSearchField> = setOf(NAME)
    }
}

/**
 * The searchable text of one recipe, flattened out of the DB row (JSON columns already decoded) and its
 * classifier names. Kept free of the SQLDelight/`Recipe` type so the matching rule below is a pure function
 * over plain strings — the UI builds this, the tests construct it directly.
 */
data class RecipeSearchFields(
    val name: String = "",
    val introduction: String = "",
    val ingredients: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val variations: List<String> = emptyList(),
    val courseName: String? = null,
    val categoryNames: List<String> = emptyList(),
    val tagNames: List<String> = emptyList(),
)

/**
 * Case-insensitive substring match across whichever fields are enabled (OR between fields) — the same rule
 * as the Swift app's `RecipeListQueryBuilder.searchCondition`, which builds `… COLLATE NOCASE LIKE '%q%'`
 * clauses joined by OR. Deliberately NOT a per-word AND search: a query typed on one platform should return
 * the same recipes on the other.
 *
 * A blank query matches everything (the list is unfiltered). An empty [options] set falls back to the name,
 * mirroring Swift's `options.isEmpty ? [.name] : options`, so turning every option off can't hide the whole
 * library with no way back.
 */
object RecipeSearch {
    fun matches(fields: RecipeSearchFields, query: String, options: Set<RecipeSearchField>): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val active = options.ifEmpty { RecipeSearchField.DEFAULTS }

        return active.any { field ->
            when (field) {
                RecipeSearchField.NAME -> fields.name.hit(q)
                RecipeSearchField.INTRODUCTION -> fields.introduction.hit(q)
                RecipeSearchField.INGREDIENTS -> fields.ingredients.anyHit(q)
                RecipeSearchField.NOTES -> fields.notes.anyHit(q)
                RecipeSearchField.VARIATIONS -> fields.variations.anyHit(q)
                RecipeSearchField.COURSE -> fields.courseName.hit(q)
                RecipeSearchField.CATEGORY -> fields.categoryNames.anyHit(q)
                RecipeSearchField.TAGS -> fields.tagNames.anyHit(q)
            }
        }
    }

    private fun String?.hit(q: String): Boolean = this?.contains(q, ignoreCase = true) == true

    private fun List<String>.anyHit(q: String): Boolean = any { it.hit(q) }
}
