package com.enuvro.saltykmp.text

/**
 * Where an ingredient line's quantity ends and the ingredient begins: "1 c" out of "1 c flour",
 * "1/2 tsp" out of "1/2 tsp salt", "2" out of "2 onions, diced" (a count, with no unit to take), and
 * nothing at all out of "pinch of salt".
 *
 * This exists so a recipe reads the same on every client: the quantity is what the detail view sets in
 * bold, and it is the part a cook checks against the bowl. The rule is the Swift app's
 * `Ingredient.parseQuantity()` in `IngredientTextParser.swift`, and the web app's `splitQuantity` in
 * `webapp/src/model.js` is the same rule again in JavaScript. Three implementations of one paragraph:
 * see `salty-contract/README.md` for why that is the arrangement rather than a shared library.
 *
 * Kept apart from [RecipeListText], which is about turning whole lists into text and back. This is a
 * display rule, and it never edits the line — [split] only says where to cut it.
 */
object IngredientQuantity {

    /**
     * A number, as a cook writes one: "2", "1.5", "1 1/2", "1/2", "1 / 2".
     *
     * The Swift app's `numberTokenPattern`, character for character.
     */
    private const val NUMBER = """\d+(?:\.\d+)?(?:\s+\d+/\d+)?(?:\s*/\s*\d+)?"""

    private val RANGE = Regex("""^($NUMBER)\s*-\s*($NUMBER)""")
    private val LEADING = Regex("""^($NUMBER)""")
    private val WORD = Regex("""^[\w.\-]+""")

    /**
     * Units of more than one word, **longest first**.
     *
     * Order matters: the first prefix match wins, so a shorter unit ahead of a longer one it prefixes
     * takes only part of the word. Written the other way round, "2 fluid ounces water" split into a
     * quantity of "2 fluid ounce" and a remainder of "s water" — which is exactly what the Swift list
     * did until September 2026.
     */
    private val MULTI_WORD_UNITS = listOf(
        "fluid ounces", "fluid ounce",
        "fl. oz.", "fl oz", "floz",
    )

    /** The single-word units, as the Swift parser lists them. */
    private val UNITS = setOf(
        "cup", "cups", "c", "c.",
        "tablespoon", "tablespoons", "tbl", "tbl.", "tbsp", "tbsp.", "tbs", "tbs.",
        "teaspoon", "teaspoons", "t", "t.", "tsp", "tsp.",
        "gram", "grams", "g", "g.",
        "kilogram", "kilograms", "kg", "kg.",
        "ounce", "ounces", "oz", "oz.",
        "pound", "pounds", "lb", "lb.", "lbs", "lbs.",
        "milliliter", "milliliters", "ml", "ml.",
        "liter", "liters", "l", "l.",
        "package", "packages", "pkg", "pkg.",
        "can", "cans",
        "bottle", "bottles",
        "piece", "pieces", "pc", "pc.",
        "dash", "dashes",
        "pinch", "pinches",
        "drop", "drops",
    )

    /** An ingredient line split for display. An empty [quantity] means the whole line is [remainder]. */
    data class Parts(val quantity: String, val remainder: String) {
        val hasQuantity: Boolean get() = quantity.isNotEmpty()
    }

    /**
     * Splits [text] into its leading quantity and the rest.
     *
     * A heading is a section name rather than an ingredient, so it is never split — but that is the
     * caller's business, because every call site is already branching on `isHeading` to draw it
     * differently, and deciding it twice is how the two decisions drift apart.
     */
    fun split(text: String): Parts {
        val trimmed = text.trim()

        val range = RANGE.find(trimmed)
        val match = range ?: LEADING.find(trimmed) ?: return Parts("", trimmed)

        // A range is normalised to one hyphen: "2 - 3" and "2-3" are the same quantity.
        val number = if (range != null) {
            "${range.groupValues[1]}-${range.groupValues[2]}"
        } else {
            match.groupValues[1]
        }
        val after = trimmed.substring(match.value.length).trim()
        if (after.isEmpty()) return Parts(number, "")

        val lowered = after.lowercase()
        for (unit in MULTI_WORD_UNITS) {
            if (lowered.startsWith(unit)) {
                // Taken from the original rather than the lowercased copy, so "Fl Oz" survives as typed.
                return Parts("$number ${after.take(unit.length)}", after.drop(unit.length).trim())
            }
        }

        val word = WORD.find(after) ?: return Parts(number, after)
        val lower = word.value.lowercase()
        if (lower in UNITS || lower.replace(".", "") in UNITS) {
            return Parts("$number ${word.value}", after.drop(word.value.length).trim())
        }
        return Parts(number, after)
    }
}
