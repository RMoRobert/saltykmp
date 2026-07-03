package com.enuvro.saltykmp.db.model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val title: String,
    val content: String,
)

@Serializable
data class Variation(
    val id: String,
    val variationName: String,
    val text: String,
)

@Serializable
data class Direction(
    val id: String,
    val isHeading: Boolean? = null,
    val text: String,
)

@Serializable
data class Ingredient(
    val id: String,
    val isHeading: Boolean = false,
    val isMain: Boolean = false,
    val text: String,
)

@Serializable
data class PreparationTime(
    val id: String,
    val type: String,
    val timeString: String,
)

@Serializable
data class NutritionInformation(
    val id: String,
    val servingSize: String? = null,
    val calories: Double? = null,
    val protein: Double? = null,
    val carbohydrates: Double? = null,
    val fat: Double? = null,
    val saturatedFat: Double? = null,
    val transFat: Double? = null,
    val fiber: Double? = null,
    val sugar: Double? = null,
    val sodium: Double? = null,
    val cholesterol: Double? = null,
    val addedSugar: Double? = null,
    val vitaminD: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val potassium: Double? = null,
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
)

@Serializable
data class ShoppingListListContents(
    val id: String,
    val isCompleted: Boolean? = false,
    val isImportant: Boolean? = false,
    val text: String,
)

@Serializable
enum class Difficulty(val rawValue: Long) {
    NOT_SET(0),
    EASY(1),
    SOMEWHAT_EASY(2),
    MEDIUM(3),
    SLIGHTLY_DIFFICULT(4),
    DIFFICULT(5),
    ;

    companion object {
        fun fromRawValue(rawValue: Long): Difficulty =
            entries.firstOrNull { it.rawValue == rawValue } ?: NOT_SET
    }
}

@Serializable
enum class Rating(val rawValue: Long) {
    NOT_SET(0),
    ONE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    ;

    companion object {
        fun fromRawValue(rawValue: Long): Rating =
            entries.firstOrNull { it.rawValue == rawValue } ?: NOT_SET
    }
}
