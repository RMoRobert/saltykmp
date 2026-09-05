package com.enuvro.saltykmp

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.enuvro.saltykmp.text.IngredientQuantity

/**
 * An ingredient line with its quantity in bold: **1 c** flour, **1/2 tsp** salt.
 *
 * The quantity is the number *and its unit*, which is what a cook checks against the bowl and the one
 * part of the line worth finding at a glance. A count with no unit emphasises the number alone (**2**
 * onions — "onions" is not a unit), and a line that opens with no number at all is drawn plain.
 *
 * Where the quantity ends is [IngredientQuantity]'s decision, shared with the read view and the chef
 * screen so the same line cannot be split two ways in one app. The weight matches the Swift app's
 * `.fontWeight(.semibold)` on the same row.
 *
 * [prefix] is for a caller that draws its own bullet: it is never part of the quantity, so it is
 * appended before the styling starts rather than concatenated into the text being parsed.
 */
fun ingredientLine(text: String, prefix: String = ""): AnnotatedString = buildAnnotatedString {
    append(prefix)
    val parts = IngredientQuantity.split(text)
    if (!parts.hasQuantity) {
        append(text)
        return@buildAnnotatedString
    }
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(parts.quantity) }
    if (parts.remainder.isNotEmpty()) append(" ${parts.remainder}")
}
