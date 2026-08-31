package com.enuvro.saltykmp.db

import com.enuvro.saltykmp.api.DeviceListEntry
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import com.enuvro.saltykmp.util.WireDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object DeviceRepository {

    /**
     * Registers the device if new, else returns its existing sync state.
     *
     * `isFirstSync` answers "has this device ever FINISHED a sync?", which is `lastSyncDate == null`
     * — not "did this row already exist?". The two used to be the same thing, back when registration
     * was the only way a `device_sync` row came into being. They are not any more: enrolment issues a
     * device sync token into this same row (see [issueToken]), so by the time a newly signed-in client
     * registers, the row is already there and the old test called it a returning device with no
     * watermark to return to.
     *
     * That distinction is load-bearing. SYNC-006 makes `isFirstSync` the thing that suppresses
     * deletion inference, so getting it wrong means a device that has never synced applies the
     * agreement rules in SYNC-007 — deleting every stamped, unmodified row this server does not have.
     * A stamp records agreement with *the server*, and a device that has never synced with this one
     * cannot know the stamps in a shared library refer to it.
     *
     * Fixing it here rather than in each client is what makes the flag mean what its name says for
     * any client, including ones not written yet; the three that exist also guard it themselves.
     *
     * Note this was reachable before device tokens too, just rarely: a client whose very first sync
     * failed after registering but before [completeSync] got exactly the same answer on its retry.
     */
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
                isFirstSync = existing[DeviceSyncs.lastSyncDate] == null,
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

    /** The read-only sibling of [getOrCreate]; `isFirstSync` means the same thing here. */
    suspend fun get(userId: String, deviceId: String): DeviceSyncInfo? = dbQuery {
        DeviceSyncs.selectAll()
            .where { (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }
            .limit(1).singleOrNull()?.let {
                DeviceSyncInfo(
                    deviceId = deviceId,
                    deviceName = it[DeviceSyncs.deviceName],
                    lastSyncDate = WireDate.format(it[DeviceSyncs.lastSyncDate]),
                    firstSyncDate = WireDate.format(it[DeviceSyncs.firstSyncDate]),
                    isFirstSync = it[DeviceSyncs.lastSyncDate] == null,
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

    /** Records that a token was used. Best-effort: it shows on user's devices page, not used authorization, etc. */
    suspend fun touchTokenUse(userId: String, deviceId: String) = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) }) {
            it[tokenLastUsed] = WireDate.nowUtc()
        }
        Unit
    }

    /**
     * Removes a device: revocation and forgetting are the same act, so the row goes.
     *
     * This used to null the hash and keep the row, on the theory that a device which later re-enrols
     * is recognisably the same device. In practice nobody wants that continuity -- re-enrolling is
     * how you replace a device, not how you resume one -- and keeping the row cost more than it paid:
     * a null hash could mean revoked, never enrolled, or signed out by a password change, and the
     * devices list had no way to tell those apart. Deleting collapses the first meaning entirely.
     *
     * The row carries the sync watermark, so removing it makes the device's next sync a first sync
     * ([getOrCreate] reports `isFirstSync` from a null `lastSyncDate`). That is the conservative
     * direction -- deletion inference is suppressed, so nothing is lost -- but it does mean a removed
     * device that comes back re-uploads rows the server deleted meanwhile. Callers warn about that.
     */
    suspend fun removeDevice(userId: String, deviceId: String): Boolean = dbQuery {
        DeviceSyncs.deleteWhere { (DeviceSyncs.deviceId eq deviceId) and (DeviceSyncs.userId eq userId) } > 0
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

    /**
     * Signs every device out. Called on password change, and from "revoke all".
     *
     * Nulls rather than deletes, unlike [removeDevice], and the asymmetry is deliberate: this fires
     * on every password change, and destroying every watermark on the account would make each device
     * re-merge its whole library on next sync -- resurrecting anything deleted server-side since.
     * Changing your password should sign devices out, not rewrite your library. Deletion stays an
     * explicit per-device act.
     *
     * The `tokenHash neq null` filter is what makes the return value a count of devices actually
     * signed out. Exposed's `update` returns rows MATCHED, so filtering only on the user reported
     * every row the account had ever registered -- "3 apps signed out" when none held a token.
     *
     * With enrolment mandatory at login and revocation deleting the row, this is now the only way a
     * null `tokenHash` comes about, so it has exactly one meaning: signed out, awaiting re-sign-in.
     */
    suspend fun revokeAllTokens(userId: String): Int = dbQuery {
        DeviceSyncs.update({ (DeviceSyncs.userId eq userId) and DeviceSyncs.tokenHash.isNotNull() }) {
            it[tokenHash] = null
            it[tokenIssuedAt] = null
        }
    }
}

/** The device a valid token belongs to. */
data class DeviceTokenOwner(val userId: String, val deviceId: String)
