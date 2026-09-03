package com.enuvro.saltykmp.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Thrown when a write names an id that already belongs to a different account. The routes answer 409.
 */
class IdOwnedByAnotherAccountException(kind: String) :
    RuntimeException("That $kind id already belongs to another account")

/**
 * Refuse a write to a row another account owns.
 *
 * Recipes, courses, categories, tags and shopping lists are keyed on `id` ALONE, while every "does
 * this exist" check is scoped by user. So a save from B naming an id A already owns found nothing,
 * fell through to the upsert, and UPDATED A's row -- including its `user_id`. A's recipe left A's
 * library and manifest without a trace, and A's devices then inferred a server-side delete and
 * removed their local copy too. Two accounts importing the same `.saltyRecipe` export is enough to
 * reach it, because an export preserves ids.
 *
 * The junction tables make the same assumption from the other side: `recipe_category` rows are keyed
 * by recipe id with no user, so two accounts holding one recipe id would share each other's
 * categories. Refusing the id outright is what keeps that impossible as well.
 *
 * A composite `(user_id, id)` primary key -- what `device_sync` already uses, and for this exact
 * reason -- is the deeper fix, and it would let two accounts hold one id rather than merely stopping
 * them from destroying each other. It is not this change because it has to migrate the junction
 * tables with it, on a database already in use.
 *
 * Two accounts inserting the SAME new id concurrently still race past this into a primary-key
 * violation, since there is no row yet to lock. That is a 500 for one of them rather than data loss,
 * and it needs both to mint the same UUID in the same instant.
 */
internal fun Table.requireNotOwnedByAnother(
    idColumn: Column<String>,
    userColumn: Column<String>,
    id: String,
    userId: String,
    kind: String,
) {
    val owner = select(userColumn).where { idColumn eq id }.limit(1).singleOrNull()?.get(userColumn)
    if (owner != null && owner != userId) throw IdOwnedByAnotherAccountException(kind)
}
