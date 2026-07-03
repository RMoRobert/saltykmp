package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.di.ImageFiles
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.createDatabase
import com.enuvro.saltykmp.di.createHttpEngine
import com.enuvro.saltykmp.di.createImageFiles
import com.enuvro.saltykmp.di.createKeyValueStore
import com.enuvro.saltykmp.di.makeThumbnail
import com.enuvro.saltykmp.sync.InMemoryTokenStore
import com.enuvro.saltykmp.sync.LocalStore
import com.enuvro.saltykmp.sync.SaltyApiClient
import com.enuvro.saltykmp.sync.SyncException
import com.enuvro.saltykmp.sync.SyncResult
import com.enuvro.saltykmp.sync.SyncService
import com.enuvro.saltykmp.sync.friendlyNetworkMessage
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Connection settings persisted via [KeyValueStore]. The password is stored obfuscated (see
 * [SimpleEncoderDecoder]) rather than in plaintext; this is not true secure storage (the key is in the app) —
 * a follow-up should move it to Keychain/Keystore, matching the Swift app.
 */
class SettingsState(private val store: KeyValueStore) {
    var serverUrl: String
        get() = store.getString("serverUrl", "http://localhost:8080")
        set(value) = store.putString("serverUrl", value)
    var username: String
        get() = store.getString("username", "")
        set(value) = store.putString("username", value)
    var password: String
        get() = SimpleEncoderDecoder.decode(store.getString("password", ""))
        set(value) = store.putString("password", SimpleEncoderDecoder.encode(value))

    /** Recipe-list sort field (a RecipeSort enum name) and direction; persisted across launches. */
    var recipeSort: String
        get() = store.getString("recipeSort", "NAME")
        set(value) = store.putString("recipeSort", value)
    var recipeSortAscending: Boolean
        get() = store.getString("recipeSortAscending", "true").toBoolean()
        set(value) = store.putString("recipeSortAscending", value.toString())

    /**
     * Parent folder for the SaltyRecipeLibrary bundle (DB + images). Empty = the app's default location.
     * Read at startup by the platform DB/image providers; changing it takes effect on the next launch.
     * Honored on desktop; mobile keeps its sandboxed default (see [customLibraryLocationSupported]).
     */
    var libraryPath: String
        get() = store.getString("libraryPath", "")
        set(value) = store.putString("libraryPath", value)

    /** When enabled, the app syncs automatically a short time after each local change. Off by default. */
    var autoSyncEnabled: Boolean
        get() = store.getString("autoSyncEnabled", "false").toBoolean()
        set(value) = store.putString("autoSyncEnabled", value.toString())

    /** Epoch millis until which auto-sync is paused (0 = not paused); set by the failure banner's "pause" action. */
    var autoSyncPausedUntil: Long
        get() = store.getString("autoSyncPausedUntil", "0").toLongOrNull() ?: 0L
        set(value) = store.putString("autoSyncPausedUntil", value.toString())

    /** A stable per-install device id (generated once, then persisted) so delta sync works across launches. */
    val deviceId: String
        get() {
            val existing = store.getString("deviceId", "")
            if (existing.isNotEmpty()) return existing
            val id = newId()
            store.putString("deviceId", id)
            return id
        }
}

/** Longest-side pixel size for cached recipe thumbnails (matches the Swift app's 300×300). */
private const val THUMBNAIL_MAX_PX = 300

/** Manual DI container — holds the database, HTTP engine, repositories, and builds a SyncService. */
class AppModule {
    private val store = createKeyValueStore()
    val settings = SettingsState(store)

    /** Copy-based library sync to a user-linked folder (Android/SAF). See [LibraryFolderLink]. */
    val libraryFolder = LibraryFolderLink(store)

    // The DB (and anything reading it) is opened lazily so [startup] can reconcile a linked folder —
    // potentially replacing the local DB file via COPY_IN — BEFORE any connection is opened on it.
    val database: AppDatabase by lazy { createDatabase() }
    private val httpEngine: HttpClientEngine = createHttpEngine()
    private val tokenStore = InMemoryTokenStore()
    val localStore: LocalStore by lazy { LocalStore(database) }
    val repository: RecipeRepository by lazy { RecipeRepository(database) }
    val imageFiles: ImageFiles = createImageFiles()

    /** App-lifetime scope for background work (debounced auto-sync). Lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Debounced automatic sync after local edits; gated on [SettingsState.autoSyncEnabled] (off by default). */
    val autoSync = AutoSyncManager(settings, appScope, sync = { sync() })

    /**
     * Run once at app launch BEFORE the UI touches [database]: reconcile the linked folder, which may pull
     * newer recipes in by replacing the local DB file (safe only while the DB is closed). Returns the
     * outcome so the UI can prompt on CONFLICT. No-op (NOT_LINKED) when no folder is linked.
     */
    suspend fun startup(): LibraryFolderSyncResult =
        if (libraryFolder.isLinked()) libraryFolder.reconcileAtStartup() else LibraryFolderSyncResult.NOT_LINKED

    /** Link a freshly-picked folder and seed/reconcile it. */
    suspend fun linkLibraryFolder(folder: io.github.vinceglb.filekit.PlatformFile): LibraryFolderSyncResult =
        libraryFolder.link(folder)

    /** Push the local library out to the linked folder (safe; never overwrites local). Manual / on background. */
    suspend fun pushLibraryFolder(): LibraryFolderSyncResult = libraryFolder.pushOut()

    /** Resolve a startup CONFLICT by keeping the app's copy (overwrites the folder). Safe any time. */
    suspend fun resolveConflictKeepingLocal(): LibraryFolderSyncResult = libraryFolder.resolveUsingLocal()

    /** Resolve a startup CONFLICT by taking the folder's copy. Call only before the DB is opened (startup gate). */
    suspend fun resolveConflictKeepingFolder(): LibraryFolderSyncResult = libraryFolder.resolveUsingFolder()

    /** Logs in and runs a full bidirectional sync; then pushes the updated library to the linked folder. */
    suspend fun sync(): SyncResult {
        val result = withSyncService { it.syncNow() }
        if (libraryFolder.isLinked()) runCatching { libraryFolder.pushOut() }
        return result
    }

    /** Wipes the local library and overwrites it with the server's contents (no uploads/deletions). */
    suspend fun forceFullResync(): SyncResult {
        val result = withSyncService { it.pullEverythingFromServer() }
        if (libraryFolder.isLinked()) runCatching { libraryFolder.pushOut() }
        return result
    }

    /** Wipes the server's contents and overwrites them with the local library (no local deletions). */
    suspend fun forceFullResyncFromLocal(): SyncResult =
        withSyncService { it.pushEverythingToServer() }

    private suspend fun <T> withSyncService(block: suspend (SyncService) -> T): T {
        val api = SaltyApiClient(settings.serverUrl.trimEnd('/'), tokenStore, httpEngine)
        try {
            api.login(settings.username, settings.password)
            return block(
                SyncService(
                    api, localStore, settings.deviceId, deviceName = "KMP App",
                    // Persist the full image, then cache a 300px thumbnail blob in the DB (like the Swift app's GRDB).
                    imageSink = { recipeId, filename, bytes, imageDate ->
                        imageFiles.save(filename, bytes)
                        localStore.setRecipeImage(recipeId, filename, makeThumbnail(bytes, THUMBNAIL_MAX_PX), imageDate)
                    },
                    imageSource = { _, filename -> imageFiles.load(filename) },
                ),
            )
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: SyncException) {
            throw e // already a friendly, HTML-free message
        } catch (e: Throwable) {
            // Server offline / unreachable / unreadable response → friendly message instead of a raw stack/HTML.
            throw SyncException(friendlyNetworkMessage(e))
        } finally {
            api.close()
        }
    }
}
