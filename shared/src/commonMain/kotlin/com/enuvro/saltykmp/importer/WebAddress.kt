package com.enuvro.saltykmp.importer

/**
 * Web addresses as schema.org values carry them: absolute, or relative to the page (contract WEB-011).
 * `commonMain` has no `java.net.URI`, so relative references are resolved here, per RFC 3986 §5.2 — the
 * resolution Swift's `URL(string:relativeTo:)` performs for SaltyCore.
 */
internal object WebAddress {

    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val parts = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(?://([^/?#]*))?([^?#]*)(\?[^#]*)?(#.*)?$""")

    /** [text] as an http(s) address, resolved against [base] when relative; null when it can't be one. */
    fun httpAddress(text: String?, base: String?): String? {
        if (text.isNullOrEmpty()) return null
        scheme.find(text)?.let { match -> return text.takeIf { isHttp(match.groupValues[1]) } }
        val resolved = base?.let { resolve(it, text) } ?: return null
        return resolved.takeIf { address -> scheme.find(address)?.let { isHttp(it.groupValues[1]) } == true }
    }

    /** Whether [text] is an absolute http(s) address. */
    fun isHttpAddress(text: String): Boolean = scheme.find(text)?.let { isHttp(it.groupValues[1]) } == true

    private fun isHttp(name: String) = name.lowercase().let { it == "http" || it == "https" }

    private fun resolve(base: String, reference: String): String? {
        val b = parts.matchEntire(base) ?: return null
        val baseScheme = b.groupValues[1]
        val authority = b.groups[2]?.value
        val basePath = b.groupValues[3]
        val baseQuery = b.groups[4]?.value.orEmpty()
        val origin = "$baseScheme:" + (authority?.let { "//$it" } ?: "")

        return when {
            reference.startsWith("//") -> "$baseScheme:$reference"
            reference.startsWith("/") -> origin + withoutDotSegments(reference)
            reference.startsWith("?") -> origin + basePath + reference
            reference.startsWith("#") -> origin + basePath + baseQuery + reference
            else -> {
                val directory = when {
                    authority != null && basePath.isEmpty() -> "/"
                    else -> basePath.substringBeforeLast('/', "") + "/"
                }
                origin + withoutDotSegments(directory + reference)
            }
        }
    }

    /** RFC 3986 §5.2.4, applied to the path part only; any query or fragment is carried through untouched. */
    private fun withoutDotSegments(pathAndMore: String): String {
        val cut = pathAndMore.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) pathAndMore.length else it }
        val path = pathAndMore.substring(0, cut)
        val output = mutableListOf<String>()
        val segments = path.split('/')
        segments.forEachIndexed { index, segment ->
            when (segment) {
                "." -> if (index == segments.lastIndex) output += ""
                ".." -> {
                    if (output.size > 1) output.removeAt(output.lastIndex)
                    if (index == segments.lastIndex) output += ""
                }
                else -> output += segment
            }
        }
        return output.joinToString("/") + pathAndMore.substring(cut)
    }
}
