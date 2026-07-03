package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.util.WireDate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/** Courses / categories / tags — small user-scoped vocabulary tables (same shape). */
object LibraryRepository {

    // Courses
    suspend fun listCourses(userId: String): List<ServerCourse> = dbQuery {
        Courses.selectAll().where { Courses.userId eq userId }.orderBy(Courses.name to SortOrder.ASC)
            .map { ServerCourse(it[Courses.id], it[Courses.name], WireDate.format(it[Courses.lastModifiedDate])) }
    }
    suspend fun countCourses(userId: String): Long = dbQuery {
        Courses.selectAll().where { Courses.userId eq userId }.count()
    }
    suspend fun upsertCourse(userId: String, c: ServerCourse): ServerCourse = dbQuery {
        Courses.upsert {
            it[id] = c.id; it[Courses.userId] = userId; it[name] = c.name
            it[lastModifiedDate] = WireDate.parse(c.lastModifiedDate)
        }
        c
    }
    suspend fun deleteCourse(userId: String, id: String): Boolean = dbQuery {
        Courses.deleteWhere { (Courses.id eq id) and (Courses.userId eq userId) } > 0
    }

    // Categories
    suspend fun listCategories(userId: String): List<ServerCategory> = dbQuery {
        Categories.selectAll().where { Categories.userId eq userId }.orderBy(Categories.name to SortOrder.ASC)
            .map { ServerCategory(it[Categories.id], it[Categories.name], WireDate.format(it[Categories.lastModifiedDate])) }
    }
    suspend fun countCategories(userId: String): Long = dbQuery {
        Categories.selectAll().where { Categories.userId eq userId }.count()
    }
    suspend fun upsertCategory(userId: String, c: ServerCategory): ServerCategory = dbQuery {
        Categories.upsert {
            it[id] = c.id; it[Categories.userId] = userId; it[name] = c.name
            it[lastModifiedDate] = WireDate.parse(c.lastModifiedDate)
        }
        c
    }
    suspend fun deleteCategory(userId: String, id: String): Boolean = dbQuery {
        Categories.deleteWhere { (Categories.id eq id) and (Categories.userId eq userId) } > 0
    }

    // Tags
    suspend fun listTags(userId: String): List<ServerTag> = dbQuery {
        Tags.selectAll().where { Tags.userId eq userId }.orderBy(Tags.name to SortOrder.ASC)
            .map { ServerTag(it[Tags.id], it[Tags.name], WireDate.format(it[Tags.lastModifiedDate])) }
    }
    suspend fun countTags(userId: String): Long = dbQuery {
        Tags.selectAll().where { Tags.userId eq userId }.count()
    }
    suspend fun upsertTag(userId: String, t: ServerTag): ServerTag = dbQuery {
        Tags.upsert {
            it[id] = t.id; it[Tags.userId] = userId; it[name] = t.name
            it[lastModifiedDate] = WireDate.parse(t.lastModifiedDate)
        }
        t
    }
    suspend fun deleteTag(userId: String, id: String): Boolean = dbQuery {
        Tags.deleteWhere { (Tags.id eq id) and (Tags.userId eq userId) } > 0
    }
}
