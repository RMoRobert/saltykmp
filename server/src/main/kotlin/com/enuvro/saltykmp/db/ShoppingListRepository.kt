package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.appJson
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Shopping lists — user-scoped, synced as whole rows.
 *
 * `contentsForList` is stored as JSON text (like the recipe list columns) rather than normalized:
 * sync is whole-row last-writer-wins, so individual items never need server-side identity. See
 * FEATURE_PLANS.md in the Salty repo for why that trade was made.
 */
object ShoppingListRepository {

    suspend fun list(userId: String): List<ServerShoppingList> = dbQuery {
        ShoppingLists.selectAll().where { ShoppingLists.userId eq userId }
            .orderBy(ShoppingLists.name to SortOrder.ASC)
            .map { it.toDto() }
    }

    suspend fun count(userId: String): Long = dbQuery {
        ShoppingLists.selectAll().where { ShoppingLists.userId eq userId }.count()
    }

    suspend fun getById(userId: String, id: String): ServerShoppingList? = dbQuery {
        ShoppingLists.selectAll()
            .where { (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId) }
            .limit(1).singleOrNull()?.toDto()
    }

    suspend fun upsert(userId: String, list: ServerShoppingList): ServerShoppingList = dbQuery {
        ShoppingLists.upsert {
            it[id] = list.id
            it[ShoppingLists.userId] = userId
            it[name] = list.name
            it[isFreeform] = list.isFreeform
            // Null contents encode as null rather than "[]", so an older client that omits the field
            // doesn't overwrite a populated list with an empty one.
            it[contentsForList] = list.contentsForList?.let { items ->
                appJson.encodeToString(ListSerializer(ShoppingListListContents.serializer()), items)
            }
            it[contentsForFreeform] = list.contentsForFreeform
            it[lastModifiedDate] = WireDate.parse(list.lastModifiedDate)
        }
        list
    }

    suspend fun delete(userId: String, id: String): Boolean = dbQuery {
        ShoppingLists.deleteWhere { (ShoppingLists.id eq id) and (ShoppingLists.userId eq userId) } > 0
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toDto() = ServerShoppingList(
        id = this[ShoppingLists.id],
        name = this[ShoppingLists.name],
        isFreeform = this[ShoppingLists.isFreeform],
        contentsForList = this[ShoppingLists.contentsForList]
            ?.takeIf { it.isNotBlank() }
            ?.let { appJson.decodeFromString(ListSerializer(ShoppingListListContents.serializer()), it) },
        contentsForFreeform = this[ShoppingLists.contentsForFreeform],
        lastModifiedDate = WireDate.format(this[ShoppingLists.lastModifiedDate]),
    )
}
