package com.enuvro.saltykmp.shopping

import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.util.newId

/**
 * Translates between a shopping list's two representations — a structured checklist and freeform
 * Markdown-ish text — and summarizes either for a list row. Kotlin port of the Swift app's
 * `ShoppingListFreeformConverter` + `ShoppingList.contentsSummary`, using the same grammar so a list
 * converted on one platform reads identically on the other.
 *
 * A list's kind is fixed at creation; neither direction runs automatically. [toFreeformText] backs the
 * explicit "Convert to Freeform Text" action, and [toItems] is its round-trip partner.
 */
object ShoppingListText {

    /**
     * Serializes checklist items using the grammar [toItems] parses, so the two round-trip: headings
     * become `# Heading`, items become `* [ ] text` / `* [x] text`. Item importance has no text form and
     * is dropped (as in the Swift app).
     */
    fun toFreeformText(items: List<ShoppingListListContents>): String = items.joinToString("\n") { item ->
        if (item.isHeading == true) {
            "# ${item.text}"
        } else {
            "* ${if (item.isCompleted == true) "[x]" else "[ ]"} ${item.text}"
        }
    }

    /**
     * Parses freeform text into checklist items:
     *  - `#`-prefixed lines (any depth) become heading rows
     *  - `*`, `-`, `+`, or `•` bullets become items; a leading `[x]`/`[X]` marks the item completed
     *  - any other non-empty line becomes a plain item
     *  - blank lines, and headings/items whose text is empty, are dropped
     *
     * [newId] is injected so tests get deterministic ids; it defaults to the same uppercase-UUID shape the
     * rest of the sync layer writes.
     */
    fun toItems(text: String, newId: () -> String = ::newItemId): List<ShoppingListListContents> {
        val items = mutableListOf<ShoppingListListContents>()
        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                val title = line.dropWhile { it == '#' }.trim()
                if (title.isEmpty()) continue
                items += ShoppingListListContents(id = newId(), isHeading = true, text = title)
                continue
            }

            var body = line
            if (body.first() in BULLETS) body = body.drop(1).trim()
            var isCompleted = false
            if (body.startsWith("[")) {
                when {
                    body.startsWith("[x]", ignoreCase = true) -> {
                        isCompleted = true
                        body = body.drop(3).trim()
                    }
                    body.startsWith("[ ]") -> body = body.drop(3).trim()
                }
            }
            if (body.isEmpty()) continue
            items += ShoppingListListContents(id = newId(), isCompleted = isCompleted, text = body)
        }
        return items
    }

    /**
     * One-line subtitle for a row in the lists screen: how much is *in* the list, not how far through it
     * you are. Heading rows are excluded from the checklist count (they group items, they aren't things to
     * buy) and blank lines from the freeform count (paragraph spacing shouldn't inflate the total).
     */
    fun contentsSummary(
        isFreeform: Boolean,
        items: List<ShoppingListListContents>,
        freeformText: String?,
    ): String {
        if (isFreeform) {
            val lines = freeformText.orEmpty().split("\n").count { it.isNotBlank() }
            return if (lines == 0) "Empty" else "$lines line${if (lines == 1) "" else "s"}"
        }
        val count = items.count { it.isHeading != true }
        return if (count == 0) "No items" else "$count item${if (count == 1) "" else "s"}"
    }

    private val BULLETS = charArrayOf('*', '-', '+', '•')

    /** Default id source — a distinct name because [toItems]'s `newId` parameter shadows the import. */
    private fun newItemId(): String = newId()
}
