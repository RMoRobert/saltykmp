package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.LibraryClassifierItem

/**
 * The wording the classifier editor's two destructive confirmations use. Ported from the Swift app's
 * `LibraryClassifiersEditViewModel`, and kept out of the composables so it can be read and tested as
 * prose — which is the only thing standing between the user and an irreversible bulk delete.
 */

/** Title for the delete confirmation, naming the single row where there is one. */
internal fun classifierDeletionTitle(rows: List<LibraryClassifierItem>, kind: ClassifierKind): String =
    if (rows.size == 1) "Delete \"${rows.first().name}\"?" else "Delete ${rows.size} ${kind.title}?"

/**
 * What the delete costs, in recipes. Recipes are never deleted — they only lose the classification,
 * which is the part worth saying out loud before a category disappears from thirty of them.
 *
 * An unused row still gets a confirmation, just a shorter one: a delete that asks only sometimes is a
 * delete you stop reading.
 */
internal fun classifierDeletionMessage(rows: List<LibraryClassifierItem>, kind: ClassifierKind): String {
    val affected = rows.sumOf { it.recipeCount }
    if (affected == 0) return "This cannot be undone."

    // One recipe can hold two of the categories/tags being deleted, so a multi-row total is an upper
    // bound. A recipe has only one course, so that total is exact.
    val many = rows.size > 1
    val mayDoubleCount = many && kind != ClassifierKind.Courses
    val count = if (affected == 1) "1 recipe" else "$affected recipes"
    val recipes = if (mayDoubleCount) "up to $count" else count

    return when (kind) {
        ClassifierKind.Courses -> {
            val subject = if (many) "these courses" else "this course"
            val remain = if (affected == 1) {
                "That recipe will remain, but its course selection will be removed."
            } else {
                "Those recipes will remain, but their course selection will be removed."
            }
            "$recipes ${if (affected == 1) "is" else "are"} currently classified with $subject. $remain " +
                "This cannot be undone."
        }

        ClassifierKind.Categories, ClassifierKind.Tags -> if (many) {
            "These ${kind.title.lowercase()} are being used by $recipes. Removing them will remove them " +
                "from those recipes, but the recipes will remain. This cannot be undone."
        } else {
            "This ${kind.singular.lowercase()} is being used by $recipes. Removing it will remove it " +
                "from those recipes, but the recipes will remain. This cannot be undone."
        }
    }
}

/** Title for the merge dialog. Counts rather than names: the names are listed right below it. */
internal fun classifierMergeTitle(rows: List<LibraryClassifierItem>, kind: ClassifierKind): String =
    "Merge ${rows.size} ${if (rows.size == 1) kind.singular else kind.title}"

/**
 * What the merge does, in the survivor's own name — which is the whole reason the dialog exists, since
 * the survivor's spelling is the one that remains.
 *
 * The rows being folded away are named while there are few enough to read, and counted after that;
 * either way they are listed in full above this sentence, so the count loses nothing.
 */
internal fun classifierMergeMessage(
    rows: List<LibraryClassifierItem>,
    survivorId: String?,
    kind: ClassifierKind,
): String {
    val survivor = rows.firstOrNull { it.id == survivorId }
        ?: return "Choose which ${kind.singular.lowercase()} to keep."
    val losing = rows.filter { it.id != survivor.id }
    if (losing.isEmpty()) return "Nothing to merge into \"${survivor.name}\"."

    val subject = if (losing.size <= 3) {
        andList(losing.map { "\"${it.name}\"" })
    } else {
        "The other ${losing.size} ${kind.title.lowercase()}"
    }
    val verb = if (losing.size == 1) "will be deleted, and its recipes" else "will be deleted, and their recipes"
    val outcome = if (kind == ClassifierKind.Courses) {
        "will use \"${survivor.name}\" instead"
    } else {
        "will be added to \"${survivor.name}\""
    }
    return "$subject $verb $outcome. The recipes themselves are not deleted. This cannot be undone."
}

/** "A", "A and B", "A, B, and C" — the Oxford-comma list Foundation's `.list(type: .and)` produces. */
private fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}
