package com.enuvro.saltykmp.importer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * A schema.org `Energy` or `Mass` value — "240 calories", "9 g", "1,299.7 mg" — as a number in the unit
 * Salty stores that nutrition field in. Rule: salty-contract SPEC.md WEB-029, WEB-L03. Mirrors SaltyCore's
 * `NutritionAmount`.
 */
internal object NutritionAmount {

    /** The unit a Salty nutrition field is stored in. */
    enum class Unit { KILOCALORIES, GRAMS, MILLIGRAMS, MICROGRAMS }

    private val amount = Regex("""^\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?|\.\d+)\s*(\S*)""")

    /**
     * [value] in [unit], or null when it isn't an amount of that kind.
     *
     * Text is `<number> <unit>` (schema.org: "e.g., '7 kg'"): digits, optionally grouped by commas in
     * threes, with an optional fraction, then the unit with or without a space, then anything at all ("9
     * grams of protein"). A number with no unit — a JSON number, or text that is only a number — is already
     * in the field's unit (WEB-L03). A unit that isn't the right kind, or isn't a unit ("12 %", "12 carbs"),
     * is no amount: storing 12 mg of sodium for "12 %" would be a wrong number.
     */
    fun value(value: JsonElement, unit: Unit): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) parse(primitive.content, unit) else primitive.content.toDoubleOrNull()
    }

    /** As [value], for text already cleaned by the reader. */
    fun parse(text: String, unit: Unit): Double? {
        val match = amount.find(text) ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val token = match.groupValues[2].trim { it in ".,;:" }.lowercase()
        if (token.isEmpty()) return number
        return convert(number, token, unit)
    }

    private fun convert(amount: Double, token: String, unit: Unit): Double? = when (unit) {
        Unit.KILOCALORIES -> when (token) {
            "kcal", "cal", "calorie", "calories", "kilocalorie", "kilocalories" -> amount
            "kj", "kilojoule", "kilojoules" -> rounded(amount / 4.184)
            else -> null
        }
        Unit.GRAMS -> grams(amount, token)?.let(::rounded)
        Unit.MILLIGRAMS -> grams(amount, token)?.let { rounded(it * 1_000) }
        Unit.MICROGRAMS -> grams(amount, token)?.let { rounded(it * 1_000_000) }
    }

    private fun grams(amount: Double, token: String): Double? = when (token) {
        "g", "gram", "grams" -> amount
        "mg", "milligram", "milligrams" -> amount / 1_000
        "mcg", "µg", "μg", "ug", "microgram", "micrograms" -> amount / 1_000_000
        "kg", "kilogram", "kilograms" -> amount * 1_000
        else -> null
    }

    /** Four decimal places: no `300.00000000000006` mg of sodium from a conversion's floating-point residue. */
    private fun rounded(value: Double): Double = (value * 10_000).roundToLong() / 10_000.0
}
