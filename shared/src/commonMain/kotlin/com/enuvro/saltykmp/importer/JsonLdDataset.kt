package com.enuvro.saltykmp.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every JSON-LD block on a page, read together as the one dataset JSON-LD 1.1 §7 says they form: node
 * references resolve across blocks, schema.org properties are found under any of the names JSON-LD
 * expansion gives them, and the page's recipes are found where the contract says to look.
 *
 * Rules: salty-contract SPEC.md §8.1 (WEB-002 to WEB-008). Mirrors SaltyCore's `JSONLDDataset`.
 */
internal class JsonLdDataset(private val blocks: List<JsonElement>) {

    /** Every node that carries an `@id` and something besides it, by that id. The first definition wins. */
    private val nodesById: Map<String, JsonObject> = buildMap { blocks.forEach { index(it, this) } }

    /**
     * The property's values, as a flat list: an array is its elements, node references (WEB-006) are the
     * nodes they name, value objects are their values, `@list` and `@set` are their arrays (WEB-007). A
     * reference to nothing, and JSON null, are no value at all.
     */
    fun values(node: JsonElement, property: String): List<JsonElement> =
        rawValue(node, property)?.let(::expand).orEmpty()

    /** Whether the node declares [property] at all, whatever its value. */
    fun has(node: JsonElement, property: String): Boolean = rawValue(node, property) != null

    /** Whether `@type` names [type], in any of the forms WEB-004 accepts. */
    fun hasType(node: JsonElement, type: String): Boolean {
        val declared = (node as? JsonObject)?.get("@type") ?: return false
        val names = if (declared is JsonArray) declared else listOf(declared)
        return names.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::localName) == type }
    }

    /**
     * The page's recipes, in document order (WEB-002, WEB-005): each block's top-level object, the elements
     * of a top-level array, the members of an `@graph` at any depth of `@graph`, and a node's `mainEntity` —
     * and nowhere else, since a recipe nested in a review or an `isBasedOn` is a stub describing some recipe,
     * not the page's. A node reached twice (the same `@id`) is returned once.
     */
    fun recipes(): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        val seenIds = mutableSetOf<String>()

        fun add(node: JsonElement) {
            if (node !is JsonObject || !hasType(node, "Recipe")) return
            val id = (node["@id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id != null && !seenIds.add(id)) return
            found += node
        }

        fun visit(value: JsonElement) {
            when (value) {
                is JsonArray -> value.forEach { visit(it) }
                is JsonObject -> {
                    add(value)
                    values(value, "mainEntity").forEach { add(it) }
                    value["@graph"]?.let { visit(it) }
                }
                else -> Unit
            }
        }

        blocks.forEach { visit(it) }
        return found
    }

    /** The property's raw value under the first of its WEB-004 names the node uses. */
    private fun rawValue(node: JsonElement, property: String): JsonElement? {
        val fields = node as? JsonObject ?: return null
        return names(property).firstNotNullOfOrNull { fields[it] }
    }

    private fun expand(value: JsonElement): List<JsonElement> = when (val resolved = resolve(value)) {
        is JsonArray -> resolved.flatMap(::expand)
        JsonNull -> emptyList()
        else -> listOf(resolved)
    }

    /** A node reference as its node, a value object as its value, `@list`/`@set` as its array. */
    private fun resolve(value: JsonElement): JsonElement {
        if (value !is JsonObject) return value
        val id = value["@id"]
        if (value.size == 1 && id is JsonPrimitive && id.isString) return nodesById[id.content] ?: JsonNull
        value["@value"]?.let { return it }
        (value["@list"] ?: value["@set"])?.let { return it }
        return value
    }

    companion object {
        /** Deeper than this and the whole block is refused rather than walked (WEB-008). */
        const val MAX_DEPTH = 64

        /**
         * One block's document, or null when it isn't one. Strict JSON (RFC 8259), as JSON-LD requires
         * (WEB-003) — the default `Json` refuses comments, `<![CDATA[` wrappers and trailing commas — and
         * nested no deeper than [MAX_DEPTH]. The caller skips a block that fails either (WEB-L01).
         */
        fun parseBlock(text: String): JsonElement? {
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            return element.takeIf { !exceedsDepth(it, 0) }
        }

        /**
         * Whether any value sits under more than [MAX_DEPTH] containers — the count Swift's decoder bounds.
         * Stops descending at the limit, so a hostile document can't make this recurse either.
         */
        private fun exceedsDepth(value: JsonElement, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return true
            return when (value) {
                is JsonArray -> value.any { exceedsDepth(it, depth + 1) }
                is JsonObject -> value.values.any { exceedsDepth(it, depth + 1) }
                else -> false
            }
        }

        private val schemaPrefixes = listOf("schema:", "http://schema.org/", "https://schema.org/")

        /** The names a schema.org property may appear under in a compacted or expanded document. */
        private fun names(property: String): List<String> = listOf(property) + schemaPrefixes.map { it + property }

        private fun localName(term: String): String =
            schemaPrefixes.firstOrNull { term.startsWith(it) }?.let { term.removePrefix(it) } ?: term

        /** Recursion is bounded by [MAX_DEPTH], which every block was read under. */
        private fun index(value: JsonElement, into: MutableMap<String, JsonObject>) {
            when (value) {
                is JsonArray -> value.forEach { index(it, into) }
                is JsonObject -> {
                    val id = value["@id"]
                    if (value.size > 1 && id is JsonPrimitive && id.isString && id.content !in into) {
                        into[id.content] = value
                    }
                    value.values.forEach { index(it, into) }
                }
                else -> Unit
            }
        }
    }
}
