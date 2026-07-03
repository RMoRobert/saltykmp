package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.util.WireDate
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

object DeviceRepository {

    /** Registers the device if new (isFirstSync=true), else returns existing sync state. */
    suspend fun getOrCreate(userId: String, deviceId: String, deviceName: String?): DeviceSyncInfo = dbQuery {
        val existing = DeviceSyncs.selectAll()
            .where { (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }
            .limit(1).singleOrNull()
        if (existing != null) {
            DeviceSyncInfo(
                deviceId = deviceId,
                deviceName = existing[DeviceSyncs.deviceName],
                lastSyncDate = WireDate.format(existing[DeviceSyncs.lastSyncDate]),
                firstSyncDate = WireDate.format(existing[DeviceSyncs.firstSyncDate]),
                isFirstSync = false,
            )
        } else {
            val now = WireDate.nowUtc()
            DeviceSyncs.insert {
                it[DeviceSyncs.deviceId] = deviceId
                it[DeviceSyncs.userId] = userId
                it[DeviceSyncs.deviceName] = deviceName
                it[firstSyncDate] = now
                it[lastSyncDate] = null
            }
            DeviceSyncInfo(
                deviceId = deviceId,
                deviceName = deviceName,
                lastSyncDate = null,
                firstSyncDate = WireDate.format(now),
                isFirstSync = true,
            )
        }
    }

    suspend fun get(userId: String, deviceId: String): DeviceSyncInfo? = dbQuery {
        DeviceSyncs.selectAll()
            .where { (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }
            .limit(1).singleOrNull()?.let {
                DeviceSyncInfo(
                    deviceId = deviceId,
                    deviceName = it[DeviceSyncs.deviceName],
                    lastSyncDate = WireDate.format(it[DeviceSyncs.lastSyncDate]),
                    firstSyncDate = WireDate.format(it[DeviceSyncs.firstSyncDate]),
                    isFirstSync = false,
                )
            }
    }

    suspend fun completeSync(userId: String, deviceId: String) = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[lastSyncDate] = WireDate.nowUtc()
        }
        Unit
    }
}
