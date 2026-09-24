package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.model.Rating

/**
 * What a screen reader says for one row of the recipe list: the name first, then the rating and the
 * favorite heart, then the row's subtitle, shortened as [spokenSubtitle] describes. The row shows one line
 * of that subtitle and fades the rest, so a listener gets about as much as a sighted user sees.
 *
 * The row replaces everything its children would say with this (`clearAndSetSemantics`). Left to merge
 * them, a row took its name from the stars' "Rated 4 of 5 stars" ahead of the text beside it, and the
 * recipe's name was never read — found through the Java Access Bridge on Windows, 2026-09-22. Only adding
 * a description instead would not do on Android: TalkBack reads a row from its children, so it heard the
 * description and then the name and subtitle again.
 */
fun recipeRowDescription(name: String, rating: Rating, isFavorite: Boolean, subtitle: String): String =
    buildList {
        add(name)
        if (rating != Rating.NOT_SET) add("rated ${rating.rawValue} of 5 stars")
        if (isFavorite) add("favorite")
        spokenSubtitle(subtitle).takeIf { it.isNotEmpty() }?.let(::add)
    }.joinToString(", ")

/** About two lines of the row's text. The first sentence usually fits; a run-on one is cut here. */
private const val SPOKEN_SUBTITLE_LIMIT = 140

/**
 * The part of a row's subtitle worth saying aloud.
 *  - An introduction is cut to its first sentence. If that sentence runs past [SPOKEN_SUBTITLE_LIMIT],
 *    it's cut at a clause, or else at a word.
 *  - A web address, which is what the subtitle shows when the source is a URL, is said as its site
 *    ("handletheheat.com"). Nobody wants a path read to them a character at a time.
 *  - Anything short, such as a source's name or "Last prepared …", is said as it is.
 */
internal fun spokenSubtitle(subtitle: String): String {
    val text = subtitle.trim()
    webSite(text)?.let { return it }
    val sentence = firstSentence(text)
    if (sentence.length <= SPOKEN_SUBTITLE_LIMIT) return sentence
    // End a run-on sentence at its last clause break (comma, semicolon, colon) when that keeps at least
    // half of it: "...pork with chiles, garlic and basil" rather than "...garlic and basil, this".
    val head = sentence.take(SPOKEN_SUBTITLE_LIMIT)
    val cut = head.indexOfLast { it in ",;:" }.takeIf { it >= SPOKEN_SUBTITLE_LIMIT / 2 }
        ?: head.lastIndexOf(' ').takeIf { it > 0 }
        ?: head.length
    return head.take(cut).trimEnd { it.isWhitespace() || it in ",;:—-" }
}

/**
 * Up to and including the first '.', '!' or '?' that is followed by a space and a capital letter, which
 * keeps "p. 444" and "e.g. the" inside the sentence they belong to. The whole text if there's none.
 */
private fun firstSentence(text: String): String {
    for (i in text.indices) {
        if (text[i] !in ".!?") continue
        var next = i + 1
        if (next >= text.length || !text[next].isWhitespace()) continue
        while (next < text.length && text[next].isWhitespace()) next++
        if (next < text.length && text[next].isUpperCase()) return text.substring(0, i + 1)
    }
    return text
}

/** "http://www.handletheheat.com/baked-smores-doughnuts/" → "handletheheat.com"; null if not a URL. */
private fun webSite(text: String): String? {
    if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) return null
    if (text.any { it.isWhitespace() }) return null
    return text.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").ifEmpty { null }
}
