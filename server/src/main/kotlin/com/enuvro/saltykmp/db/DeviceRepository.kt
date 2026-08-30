package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.DeviceListEntry
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.util.WireDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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

    /* ------------------------------------------------------------- device sync tokens -- */

    /**
     * Stores the hash of a freshly minted token against a device, replacing any previous one.
     *
     * The row is created if this device has never synced, so enrolment does not depend on the
     * client having registered first. One token per device by construction: re-enrolling the same
     * device invalidates the old token rather than accumulating credentials nobody can see.
     */
    suspend fun issueToken(userId: String, deviceId: String, deviceName: String?, tokenHash: String) = dbQuery {
        val now = WireDate.nowUtc()
        val updated = DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[DeviceSyncs.tokenHash] = tokenHash
            it[tokenIssuedAt] = now
            it[tokenLastUsed] = null
            if (deviceName != null) it[DeviceSyncs.deviceName] = deviceName
        }
        if (updated == 0) {
            DeviceSyncs.insert {
                it[DeviceSyncs.userId] = userId
                it[DeviceSyncs.deviceId] = deviceId
                it[DeviceSyncs.deviceName] = deviceName
                it[firstSyncDate] = now
                it[DeviceSyncs.tokenHash] = tokenHash
                it[tokenIssuedAt] = now
            }
        }
        Unit
    }

    /**
     * Resolves a presented token's hash to the device that owns it, or null.
     *
     * Rejects a token issued before the user's last password change even if revocation somehow
     * missed it — the same freshness rule the JWT validator applies to `iat`, so "change your
     * password" reliably means "sign everything out" whichever credential a client holds.
     */
    suspend fun findByTokenHash(tokenHash: String): DeviceTokenOwner? = dbQuery {
        val row = DeviceSyncs.selectAll()
            .where { DeviceSyncs.tokenHash eq tokenHash }
            .limit(1).singleOrNull() ?: return@dbQuery null

        val userId = row[DeviceSyncs.userId]
        val issued = row[DeviceSyncs.tokenIssuedAt]
        val changedAt = Users.selectAll().where { Users.id eq userId }.limit(1)
            .singleOrNull()?.get(Users.passwordChangedAt)
        if (issued == null) return@dbQuery null
        if (changedAt != null && issued.isBefore(changedAt)) return@dbQuery null

        DeviceTokenOwner(userId = userId, deviceId = row[DeviceSyncs.deviceId])
    }

    /** Records that a token was used. Best-effort: it drives the devices page, not authorisation. */
    suspend fun touchTokenUse(userId: String, deviceId: String) = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[tokenLastUsed] = WireDate.nowUtc()
        }
        Unit
    }

    /**
     * Revokes one device. Nulls the hash rather than deleting the row, so first/last sync dates
     * survive: a revoked device that later re-enrols is recognisably the same device.
     */
    suspend fun revokeToken(userId: String, deviceId: String): Boolean = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[tokenHash] = null
            it[tokenIssuedAt] = null
        } > 0
    }

    /**
     * Every device for a user, for the devices page.
     *
     * Returns names and dates only — never the hash. `hasToken` distinguishes a device that can
     * currently sync from one that has been revoked but whose history is worth keeping visible.
     */
    suspend fun listForUser(userId: String): List<DeviceListEntry> = dbQuery {
        DeviceSyncs.selectAll()
            .where { DeviceSyncs.userId eq userId }
            .map { row ->
                DeviceListEntry(
                    deviceId = row[DeviceSyncs.deviceId],
                    deviceName = row[DeviceSyncs.deviceName],
                    firstSyncDate = WireDate.format(row[DeviceSyncs.firstSyncDate]),
                    lastSyncDate = WireDate.format(row[DeviceSyncs.lastSyncDate]),
                    tokenLastUsed = WireDate.format(row[DeviceSyncs.tokenLastUsed]),
                    hasToken = row[DeviceSyncs.tokenHash] != null,
                )
            }
            .sortedWith(compareByDescending<DeviceListEntry> { it.tokenLastUsed ?: it.lastSyncDate ?: "" })
    }

    /** Renames a device. Names are for humans; the id remains the identity. */
    suspend fun renameDevice(userId: String, deviceId: String, name: String): Boolean = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[deviceName] = name
        } > 0
    }

    /** Revokes every device for a user. Called on password change, and from "revoke all". */
    suspend fun revokeAllTokens(userId: String): Int = dbQuery {
        DeviceSyncs.update({ DeviceSyncs.userId eq userId }) {
            it[tokenHash] = null
            it[tokenIssuedAt] = null
        }
    }
}

/** The device a valid token belongs to. */
data class DeviceTokenOwner(val userId: String, val deviceId: String)
