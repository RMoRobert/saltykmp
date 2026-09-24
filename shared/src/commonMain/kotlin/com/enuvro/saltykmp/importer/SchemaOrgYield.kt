package com.enuvro.saltykmp.importer

/**
 * A recipe's yield, and how many it serves, from schema.org `recipeYield` values.
 * Rules: salty-contract SPEC.md WEB-023 (yield) and WEB-024 (servings, Salty's own rule). Mirrors SaltyCore's
 * `SchemaOrgYield`.
 */
internal class SchemaOrgYield(private val values: List<Value>) {

    /**
     * One `recipeYield` value, read: what it says as text, whether it is just a number (a count for
     * machines, not a phrase for people), and — when it is a bare whole number, or a QuantitativeValue with
     * no unit or a servings unit — the servings it counts.
     */
    data class Value(val text: String, val isNumber: Boolean, val servingsCount: Int?)

    /** Of several values, the first that is not just a number, else the first number. */
    val text: String get() = values.firstOrNull { !it.isNumber }?.text ?: values.firstOrNull()?.text.orEmpty()

    /**
     * How many it serves (WEB-024), or null when nothing says so. Not "the first number in the yield":
     * "1 loaf" is not one serving, and `1 9" pie (8 servings)` serves eight.
     */
    fun servings(servingSize: String?): Int? =
        values.firstNotNullOfOrNull { it.servingsCount }
            ?: values.firstNotNullOfOrNull { statedServings(it.text) }
            ?: servingSize?.let(::statedServings)

    companion object {
        private const val RANGE = """(\d+)(?:\s*(?:-|–|—|to)\s*\d+)?"""
        private val patterns = listOf(
            Regex(RANGE + """\s*(?:servings?|portions?|people|persons?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:serves|serving)\s*:?\s*""" + RANGE, RegexOption.IGNORE_CASE),
        )

        /**
         * A count of servings or people stated in text: a number directly followed by servings, portions,
         * people or persons, or directly after "serves"/"serving". The first number of a range; the earliest
         * statement in the text, whichever form it takes. A count too long to be a number is no count.
         */
        fun statedServings(text: String): Int? {
            val earliest = patterns.mapNotNull { it.find(text) }.minByOrNull { it.range.first } ?: return null
            return earliest.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        }

        /** Words that make a QuantitativeValue's unit a count of servings. */
        fun isServingsUnit(unit: String): Boolean =
            unit.trim().lowercase() in setOf("serving", "servings", "portion", "portions", "people", "person", "persons")
    }
}
