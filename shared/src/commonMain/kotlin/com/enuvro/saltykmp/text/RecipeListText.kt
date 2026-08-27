package com.enuvro.saltykmp.text

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.util.newId

/**
 * Text ↔ model conversion for a recipe's ingredient and direction lists — what the "Edit as text" bulk
 * editors round-trip through. Ports the Swift app's `IngredientTextParser` and `DirectionTextParser`, so
 * a recipe written as text on one platform parses the same way on the other.
 *
 * The formats are deliberately different from each other, because the two lists are:
 * - **Ingredients** are one per line. A blank line before a line makes it a heading; so does a trailing
 *   colon. A trailing `[*]` marks a main ingredient.
 * - **Directions** are paragraphs, so a *single* blank line separates steps and consecutive non-blank
 *   lines join into one step. That leaves the single blank line taken, so a heading needs *two* blank
 *   lines before it — or, again, a trailing colon.
 *
 * Both parsers mint fresh row ids: a bulk edit replaces the list wholesale, and nothing outside the
 * recipe references these ids (the junction tables key on the recipe, not on its lines).
 */
object RecipeListText {

    // ---- Ingredients ----

    /**
     * Parses the ingredients box. Blank lines are structural (they mark the next line as a heading) and
     * are never themselves rows.
     */
    fun parseIngredients(text: String): List<Ingredient> {
        val lines = text.lines()
        val result = mutableListOf<Ingredient>()

        lines.forEachIndexed { index, raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed

            val headingByBlankLine = index > 0 && lines[index - 1].isBlank()
            val headingByColon = line.endsWith(":")
            if (headingByColon) line = line.dropLast(1)
            val isHeading = headingByBlankLine || headingByColon

            // A heading isn't a thing to buy, so the main-ingredient marker doesn't apply to it.
            var isMain = false
            if (!isHeading && line.endsWith(MAIN_MARKER)) {
                isMain = true
                line = line.dropLast(MAIN_MARKER.length).trim()
            }

            result += Ingredient(id = newId(), isHeading = isHeading, isMain = isMain, text = line)
        }

        return result
    }

    /** The inverse: what the editor puts in the box. Re-parsing this returns the same list. */
    fun formatIngredients(ingredients: List<Ingredient>): String {
        val lines = mutableListOf<String>()
        ingredients.forEach { ingredient ->
            if (ingredient.isHeading) {
                lines += ""
                lines += ingredient.text
            } else {
                lines += if (ingredient.isMain) "${ingredient.text} $MAIN_MARKER" else ingredient.text
            }
        }
        return lines.joinToString("\n")
    }

    // ---- Directions ----

    /**
     * Parses the directions box, joining wrapped lines into one step.
     *
     * A step ends at a blank line that has content after it, or at the next heading. Trailing blank
     * lines are not a break — they're just the end of the box.
     */
    fun parseDirections(text: String): List<Direction> {
        val lines = text.lines()
        val result = mutableListOf<Direction>()

        var i = 0
        while (i < lines.size) {
            var line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            val headingByDoubleBlank = i > 1 && lines[i - 1].isBlank() && lines[i - 2].isBlank()
            val headingByColon = line.endsWith(":")
            if (headingByColon) line = line.dropLast(1)
            val isHeading = headingByDoubleBlank || headingByColon

            var stepText = line
            var j = i + 1
            // A heading is a single line by definition; only a step swallows the lines under it.
            if (!isHeading) {
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isEmpty()) {
                        // A blank run ends this step only if something follows it; trailing blanks don't.
                        var k = j + 1
                        while (k < lines.size && lines[k].isBlank()) k++
                        if (k < lines.size) break
                        j++
                        continue
                    }
                    if (next.endsWith(":")) break // the next heading starts here
                    stepText += " $next"
                    j++
                }
            }

            result += Direction(id = newId(), isHeading = isHeading, text = stepText)
            // j is i+1 for a heading (the loop above never ran), so this consumes exactly one line there.
            i = j
        }

        return result
    }

    /**
     * The inverse. Steps are separated by one blank line and headings preceded by two, which is exactly
     * what [parseDirections] reads back.
     */
    fun formatDirections(directions: List<Direction>): String {
        val lines = mutableListOf<String>()
        directions.forEachIndexed { index, direction ->
            if (direction.isHeading == true) {
                lines += ""
                lines += ""
                lines += direction.text
            } else {
                if (index > 0 && lines.isNotEmpty()) lines += ""
                lines += direction.text
            }
        }
        return lines.joinToString("\n")
    }

    // ---- Clean up ----

    /**
     * Strips the list decoration that comes with text pasted from a web page or a document: bullet
     * characters, and — for directions — the step numbers ("1.", "2)") that the editor renders itself.
     *
     * Ingredients keep any leading digits, since an ingredient line *starts* with its quantity ("2 cups
     * flour"): stripping "2." there would eat the recipe. This asymmetry is the Swift app's too.
     */
    fun cleanUp(text: String, stripNumbering: Boolean): String =
        text.lines().joinToString("\n") { raw ->
            var line = raw.trim()
            val marker = LIST_MARKERS.firstOrNull { line.startsWith(it) }
            if (marker != null) line = line.removePrefix(marker)
            if (stripNumbering) line = line.replaceFirst(NUMBER_PREFIX, "")
            line.trim()
        }

    private const val MAIN_MARKER = "[*]"
    private val LIST_MARKERS = listOf("*", "-", "•", "○", "▪", "▫", "‣", "⁃")
    private val NUMBER_PREFIX = Regex("""^\d+[.)]\s*""")
}
