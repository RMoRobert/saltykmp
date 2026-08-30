package com.enuvro.saltykmp.importer

/**
 * Turns the HTML character references that survive into scraped text back into the characters they
 * stand for.
 *
 * One pass over the string, which is what makes an already-escaped `&amp;lt;` resolve to the literal
 * `&lt;` rather than being decoded twice into `<`. The chain of `replace` calls this replaced got the
 * same answer for the handful of entities it listed — `&amp;` last was the trick — but it could only
 * ever decode the entities somebody thought to list, and recipe plugins write a numeric reference for
 * every fraction they print. A WordPress ingredient list arrives full of `&#8531;` and `&frac12;`.
 *
 * The Swift and .NET clients hand this job to a full HTML decoder (SwiftSoup's `Entities`,
 * `WebUtility.HtmlDecode`); there is no such thing in commonMain, so what is recognised here is stated
 * explicitly: every numeric reference, the HTML 4 Latin-1 names (which is where the fractions, degrees,
 * accents and `&times;` live), and the punctuation names that turn up in recipe prose. A reference this
 * doesn't know is left exactly as it was found, which is what the other two do with an unknown one too.
 *
 * A trailing `;` is required. HTML's legacy entities are recognisable without one, and under that rule
 * "&notify" decodes to "¬ify" — not a trade worth making for text that always arrives terminated.
 */
object HtmlEntities {

    /** Past this, a `;` is punctuation further along the line rather than the end of a reference. */
    private const val MAX_REFERENCE_LENGTH = 32

    fun decode(text: String): String {
        if ('&' !in text) return text

        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            val char = text[i]
            if (char != '&') {
                out.append(char)
                i++
                continue
            }

            val end = text.indexOf(';', i + 1)
            val reference = if (end in 0..(i + MAX_REFERENCE_LENGTH)) referenceAt(text, i + 1, end) else null

            if (reference == null) {
                // Not a reference we know: the '&' is just an ampersand. Note that i advances by one
                // rather than past `end`, so "AT&T &amp; Sons" still decodes the real entity after it.
                out.append(char)
                i++
            } else {
                out.append(reference)
                i = end + 1
            }
        }

        return out.toString()
    }

    /** The characters `text[start until end]` stands for, or null when it isn't a reference. */
    private fun referenceAt(text: String, start: Int, end: Int): String? {
        if (end <= start) return null
        val body = text.substring(start, end)

        if (body[0] != '#') return NAMED[body]?.let(::codePoint)

        val digits = body.substring(1)
        val code = when {
            digits.isEmpty() -> null
            digits[0] == 'x' || digits[0] == 'X' -> digits.substring(1).toIntOrNull(16)
            else -> digits.toIntOrNull()
        }

        return code?.let(::codePoint)
    }

    /**
     * A code point as text. Null for anything that isn't a character: 0, past the last plane, and the
     * surrogate range, which would otherwise put an unpaired surrogate into the string.
     */
    private fun codePoint(code: Int): String? = when {
        code < 1 || code > 0x10FFFF || code in 0xD800..0xDFFF -> null
        code <= 0xFFFF -> code.toChar().toString()
        else -> {
            val offset = code - 0x10000
            charArrayOf(
                (0xD800 + (offset shr 10)).toChar(),
                (0xDC00 + (offset and 0x3FF)).toChar(),
            ).concatToString()
        }
    }

    /**
     * The HTML 4 Latin-1 names, in code-point order from U+00A0 — so the name's position in this list
     * IS its character, and the table can't drift out of step with itself.
     */
    private val LATIN_1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(" ")

    /** The ASCII escapes, plus the punctuation and symbols recipe prose actually uses. */
    private val OTHER = mapOf(
        "quot" to 34, "amp" to 38, "apos" to 39, "lt" to 60, "gt" to 62,
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376,
        "fnof" to 402, "circ" to 710, "tilde" to 732,
        "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201,
        "ndash" to 8211, "mdash" to 8212,
        "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
        "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222,
        "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230,
        "permil" to 8240, "prime" to 8242, "Prime" to 8243,
        "lsaquo" to 8249, "rsaquo" to 8250, "frasl" to 8260, "euro" to 8364, "trade" to 8482,
        "larr" to 8592, "uarr" to 8593, "rarr" to 8594, "darr" to 8595,
        "minus" to 8722, "radic" to 8730, "infin" to 8734,
        "ne" to 8800, "le" to 8804, "ge" to 8805,
    )

    private val NAMED: Map<String, Int> =
        LATIN_1.mapIndexed { index, name -> name to 0xA0 + index }.toMap() + OTHER
}
