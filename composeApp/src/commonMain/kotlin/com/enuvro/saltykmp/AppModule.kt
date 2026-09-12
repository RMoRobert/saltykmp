package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.LibraryClassifierEditor
import com.enuvro.saltykmp.db.LibraryDuplicateMerger
import com.enuvro.saltykmp.di.ImageFiles
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.SECRET_KEY_PASSWORD
import com.enuvro.saltykmp.di.SECRET_KEY_SYNC_TOKEN
import com.enuvro.saltykmp.di.SecretStore
import com.enuvro.saltykmp.di.createSecretStore
import com.enuvro.saltykmp.di.createDatabase
import com.enuvro.saltykmp.di.createHttpEngine
import com.enuvro.saltykmp.di.createImageFiles
import com.enuvro.saltykmp.di.createKeyValueStore
import com.enuvro.saltykmp.di.convertImageToJpeg
import com.enuvro.saltykmp.di.makeThumbnail
import com.enuvro.saltykmp.importer.RecipeWebImporter
import com.enuvro.saltykmp.search.RecipeSearchField
import com.enuvro.saltykmp.sync.InMemoryTokenStore
import com.enuvro.saltykmp.sync.LocalStore
import com.enuvro.saltykmp.sync.SaltyApiClient
import com.enuvro.saltykmp.sync.SyncException
import com.enuvro.saltykmp.sync.SyncProgress
import com.enuvro.saltykmp.sync.SyncResult
import com.enuvro.saltykmp.sync.SyncService
import com.enuvro.saltykmp.sync.friendlyNetworkMessage
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Connection settings persisted via [KeyValueStore], except the password, which goes to [SecretStore] --
 * the OS credential vault wherever the platform has one that works, and obfuscated prefs otherwise.
 */
class SettingsState(
    private val store: KeyValueStore,
    private val secrets: SecretStore = createSecretStore(store),
) {
    var serverUrl: String
        get() = store.getString("serverUrl", "http://localhost:8080")
        set(value) = store.putString("serverUrl", value)
    var username: String
        get() = store.getString("username", "")
        set(value) = store.putString("username", value)

    /**
     * Master switch for Salty Server sync ("Enable sync with Salty Server"). Never written by builds
     * that predate it, so the default matters: a device that already holds a sync credential was
     * plainly using the server and keeps syncing without a visit to Settings; everyone else starts off.
     */
    var serverUse: Boolean
        get() = store.getString("serverUse", "").let {
            if (it.isEmpty()) syncToken.isNotEmpty() || password.isNotEmpty() else it.toBoolean()
        }
        set(value) = store.putString("serverUse", value.toString())

    /**
     * A password saved by a pre-token build, kept only so its one remaining use — being traded for a
     * sync token on the next sync — still works. Nothing writes a new one: connecting a device passes
     * the password straight through [AppModule.connectDevice] without storing it.
     */
    var password: String
        get() = secrets.get(SECRET_KEY_PASSWORD).orEmpty()
        set(value) {
            if (value.isEmpty()) secrets.clear(SECRET_KEY_PASSWORD)
            else secrets.put(SECRET_KEY_PASSWORD, value, account = username)
        }

    /**
     * The per-device sync token, once enrolled. Replaces [password] as the thing this device keeps:
     * it can only sync, so it is safe to leave on the device in a way a password never was.
     */
    var syncToken: String
        get() = secrets.get(SECRET_KEY_SYNC_TOKEN).orEmpty()
        set(value) {
            if (value.isEmpty()) secrets.clear(SECRET_KEY_SYNC_TOKEN)
            else secrets.put(SECRET_KEY_SYNC_TOKEN, value, account = username)
        }

    /** Where secrets ([syncToken], and any legacy [password]) are kept, for the Settings caption,
     *  e.g. "Windows Credential Manager". */
    val passwordStoreName: String get() = secrets.backendName

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

    /**
     * Which recipe fields the list search looks at (Swift's "Search Options"). Persisted as a comma-joined
     * list of [RecipeSearchField] names; unknown names (written by a newer build) are ignored rather than
     * throwing. An empty stored value means "never set" → the default, not "search nothing".
     */
    var searchFields: Set<RecipeSearchField>
        get() = store.getString("recipeSearchFields", "")
            .split(',')
            .mapNotNull { name -> RecipeSearchField.entries.firstOrNull { it.name == name } }
            .toSet()
            .ifEmpty { RecipeSearchField.DEFAULTS }
        set(value) = store.putString("recipeSearchFields", value.joinToString(",") { it.name })

    /**
     * Drawer sections the user has collapsed, by [com.enuvro.saltykmp.ClassifierKind] name. Persisted because
     * a library with dozens of tags is collapsed once and should stay that way; default is all expanded.
     */
    var collapsedDrawerSections: Set<String>
        get() = store.getString("collapsedDrawerSections", "").split(',').filter { it.isNotBlank() }.toSet()
        set(value) = store.putString("collapsedDrawerSections", value.joinToString(","))

    /**
     * Chef Mode's text-size stepper position; see [ChefTextSize]. An unparseable or absent value falls
     * back to the default, so a fresh install opens Chef Mode a step or two above the reading size.
     *
     * Persisted, unlike the cooking progress it sits beside: how large you like the type is a fact
     * about the tablet propped against the toaster, where "I am on step four" is only true right now.
     */
    internal var chefTextSizeLevel: Int
        get() = store.getString("chefTextSizeLevel", "").toIntOrNull() ?: ChefTextSize.DEFAULT_LEVEL
        set(value) = store.putString("chefTextSizeLevel", ChefTextSize.clamped(value).toString())

    /** How Chef Mode presents the directions; see [ChefDisplayStyle]. Stored as the enum name. */
    internal var chefDisplayStyle: ChefDisplayStyle
        get() = store.getString("chefDisplayStyle", "")
            .let { stored -> ChefDisplayStyle.entries.firstOrNull { it.name == stored } }
            ?: ChefDisplayStyle.AllSteps
        set(value) = store.putString("chefDisplayStyle", value.name)

    /** When enabled, the app syncs automatically a short time after each local change. Off by default. */
    var autoSyncEnabled: Boolean
        get() = store.getString("autoSyncEnabled", "false").toBoolean()
        set(value) = store.putString("autoSyncEnabled", value.toString())

    /**
     * How tightly the UI packs itself; see [UiDensity].
     *
     * Stored as the enum name, with an unrecognised or absent value falling back to
     * [platformDefaultDensity] — so a fresh install follows its platform, and a build that later changes
     * its platform default moves anyone who never made a choice along with it.
     */
    internal var uiDensity: UiDensity
        get() = store.getString("uiDensity", "")
            .let { stored -> UiDensity.entries.firstOrNull { it.name == stored } }
            ?: platformDefaultDensity
        set(value) = store.putString("uiDensity", value.name)

    /** Epoch millis of the last successful sync with the server (0 = never); the "Last synced:" line. */
    var lastSyncAt: Long
        get() = store.getString("lastSyncAt", "0").toLongOrNull() ?: 0L
        set(value) = store.putString("lastSyncAt", value.toString())

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

/**
 * How this client identifies itself in the account's devices list. Shared by enrolment and sync
 * registration so one device is one row. Renaming it there is the way to tell two installs apart.
 */
private const val SYNC_DEVICE_NAME = "KMP App"

/** Longest-side pixel size for cached recipe thumbnails (matches the Swift app's 300×300). */
internal const val THUMBNAIL_MAX_PX = 300

/** Quiet period after the last edit before the library is copied to the linked folder. */
private val FOLDER_PUSH_DEBOUNCE = 45.seconds

/**
 * Manual DI container — holds the database, HTTP engine, repositories, and builds a SyncService.
 *
 * PROCESS-lifetime, reached through [shared] rather than constructed by whoever needs it. It used to
 * be `remember { AppModule() }` inside the root composable, which is per COMPOSITION — and an Android
 * configuration change (rotating the phone) throws the composition away and builds a new one. That
 * opened a second SQLite driver on the same file while the first stayed open, started a second
 * auto-sync collector and folder-push debouncer beside the originals, and ran the startup reconcile
 * again over a live database.
 */
class AppModule {

    companion object {
        private var instance: AppModule? = null

        /**
         * The process's one module, created on first use.
         *
         * Created and read from the composition, which is the main thread on every target, so this
         * needs no locking; there is exactly one Salty UI per process on all four.
         */
        fun shared(): AppModule = instance ?: AppModule().also { instance = it }
    }

    private val store = createKeyValueStore()
    val settings = SettingsState(store)

    private val _uiDensity = MutableStateFlow(settings.uiDensity)

    /**
     * The active [UiDensity]. A flow rather than a plain read of [SettingsState.uiDensity] because the
     * toggle lives in Settings but the value is consumed at the very top of the tree, by [SaltyTheme] —
     * flipping it has to repaint the whole app, not just the screen the switch is on.
     */
    internal val uiDensity: StateFlow<UiDensity> = _uiDensity.asStateFlow()

    internal fun setUiDensity(density: UiDensity) {
        settings.uiDensity = density
        _uiDensity.value = density
    }

    /**
     * Copy-based library sync to a user-linked folder (Android/SAF, iOS document picker). See [LibraryFolderLink].
     * The "is this install empty?" hint lets linking a folder on a fresh device adopt the folder's library
     * instead of prompting; it opens the DB, which is fine because linking happens from Settings.
     */
    val libraryFolder = LibraryFolderLink(store, isLocalLibraryEmpty = { repository.debugRecipeCount() == 0 })

    // The DB (and anything reading it) is opened lazily so [startup] can reconcile a linked folder —
    // potentially replacing the local DB file via COPY_IN — BEFORE any connection is opened on it.
    val database: AppDatabase by lazy { createDatabase() }
    private val httpEngine: HttpClientEngine = createHttpEngine()
    private val tokenStore = InMemoryTokenStore()
    val localStore: LocalStore by lazy { LocalStore(database) }
    val repository: RecipeRepository by lazy { RecipeRepository(database) }
    val shoppingLists: ShoppingListStore by lazy { ShoppingListStore(database, localStore) }

    // The classifier editor's two bulk actions. Both are user-initiated, so both move the affected
    // recipes' clocks — unlike [localStore]'s single-row deletes, which exist to apply what a sync
    // already decided. Call [onLocalChange] after either, as with every other local edit.
    val classifierEditor: LibraryClassifierEditor by lazy { LibraryClassifierEditor(database) }
    val classifierMerger: LibraryDuplicateMerger by lazy { LibraryDuplicateMerger(database) }
    val imageFiles: ImageFiles = createImageFiles()

    /**
     * Chef Mode's cooking progress, keyed by recipe id. App-level and in-memory: leaving Chef Mode to
     * look something up and coming back returns to the same checked ingredients and current step, and
     * none of it outlives the process. See [ChefSessionStore].
     */
    internal val chefSessions = ChefSessionStore()

    /**
     * App-lifetime scope for background work (debounced auto-sync). Lives as long as the process.
     *
     * `internal` so a screen can start work that must outlive it -- a sync started from Settings used
     * to run on the screen's own scope and was cancelled halfway by pressing Back.
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One sync at a time, whichever started it.
     *
     * Manual, menu, automatic and the two force paths all reached [withSyncService] with nothing
     * between them. An auto-sync landing in the middle of "Delete Local, Pull from Server" compared a
     * half-restored library against the full server manifest, and every recipe not yet restored read
     * as one this device had deleted. The empty-library guard did not help: the library was not
     * empty, just incomplete.
     */
    private val syncMutex = Mutex()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)

    /**
     * What the running sync is doing, or null while idle. Set from whichever coroutine is syncing — a
     * MutableStateFlow is safe to write from any thread, and Compose collects it on the main one.
     *
     * Shared by manual and automatic syncs, which cannot overlap: every path into a sync goes through
     * [withSyncService], and that takes [syncMutex].
     */
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    /** Fetches + parses a recipe from a web page's schema.org JSON-LD (Settings-free "Import from Web"). */
    val webImporter: RecipeWebImporter by lazy { RecipeWebImporter(httpEngine) }

    /** Debounced automatic sync after local edits; gated on [SettingsState.autoSyncEnabled] (off by default). */
    val autoSync = AutoSyncManager(settings, appScope, sync = { sync() })

    // Debounced push of the library to the linked folder after edits (a burst of edits → one copy). The push
    // itself is a no-op when the content hash hasn't moved, so over-triggering is cheap.
    private val folderPushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    init {
        @OptIn(FlowPreview::class)
        appScope.launch { folderPushRequests.debounce(FOLDER_PUSH_DEBOUNCE).collect { pushLibraryFolderQuietly() } }
    }

    /**
     * Whether [startup] has already run in this process.
     *
     * Its reconcile may REPLACE the database file, which is safe only while the database is closed.
     * The composable that calls it re-runs its effect whenever the composition is rebuilt, so without
     * this a rotation ran the reconcile again on top of a live library.
     */
    private var startupDone = false

    /** Call after any local library change (recipe/classifier/image add, edit, delete). Fans out to every syncer. */
    fun onLocalChange() {
        autoSync.notifyChange()
        if (libraryFolder.isLinked()) folderPushRequests.tryEmit(Unit)
    }

    /**
     * Call when the app goes to the background (lifecycle ON_STOP): the last chance to get recent edits into
     * the linked folder before the process may be killed. Runs in the app scope so it outlives the composition.
     */
    fun onAppBackground() {
        if (libraryFolder.isLinked()) appScope.launch { pushLibraryFolderQuietly() }
    }

    private suspend fun pushLibraryFolderQuietly() {
        val result = runCatching { pushFolderExclusively() }.getOrElse { LibraryFolderSyncResult.ERROR }
        println("LibraryFolderLink background push: $result")
    }

    /**
     * Copy the library out to the linked folder, with no sync running.
     *
     * The copy checkpoints the WAL and then copies the `.sqlite` file. A sync committing between those
     * two steps -- or an automatic checkpoint part-way through the copy -- puts a TORN database in the
     * folder, which the other device then adopts on its next launch. Nothing used to stop that: the
     * 45-second debounce and the on-background push both fired without regard to what sync was doing.
     * [syncMutex] is the same lock every sync path takes, so this simply waits its turn.
     *
     * Off the caller's thread as well. `states()`, `copyIn` and `copyOut` walk a SAF document tree on
     * Android, where `findFile` enumerates the whole directory -- seconds, on a cloud-backed folder --
     * and the calls arrive from a LaunchedEffect and from Settings, both on the main thread.
     */
    private suspend fun pushFolderExclusively(): LibraryFolderSyncResult =
        syncMutex.withLock { withContext(Dispatchers.Default) { libraryFolder.pushOut() } }

    /**
     * Run once at app launch BEFORE the UI touches [database]: reconcile the linked folder, which may pull
     * newer recipes in by replacing the local DB file (safe only while the DB is closed). Returns the
     * outcome so the UI can prompt on CONFLICT. No-op (NOT_LINKED) when no folder is linked.
     */
    suspend fun startup(): LibraryFolderSyncResult = withContext(Dispatchers.Default) {
        if (startupDone) return@withContext LibraryFolderSyncResult.NOT_LINKED
        startupDone = true
        if (libraryFolder.isLinked()) libraryFolder.reconcileAtStartup()
        else LibraryFolderSyncResult.NOT_LINKED
    }

    /** Link a freshly-picked folder and seed/reconcile it. */
    suspend fun linkLibraryFolder(folder: io.github.vinceglb.filekit.PlatformFile): LibraryFolderSyncResult =
        withContext(Dispatchers.Default) { libraryFolder.link(folder) }

    /** Push the local library out to the linked folder (safe; never overwrites local). Manual / on background. */
    suspend fun pushLibraryFolder(): LibraryFolderSyncResult = pushFolderExclusively()

    /** Resolve a startup CONFLICT by keeping the app's copy (overwrites the folder). Safe any time. */
    suspend fun resolveConflictKeepingLocal(): LibraryFolderSyncResult =
        withContext(Dispatchers.Default) { libraryFolder.resolveUsingLocal() }

    /** Resolve a startup CONFLICT by taking the folder's copy. Call only before the DB is opened (startup gate). */
    suspend fun resolveConflictKeepingFolder(): LibraryFolderSyncResult =
        withContext(Dispatchers.Default) { libraryFolder.resolveUsingFolder() }

    /** Logs in and runs a full bidirectional sync; then pushes the updated library to the linked folder. */
    suspend fun sync(): SyncResult {
        val result = withSyncService { it.syncNow() }
        recordSyncSucceeded()
        if (libraryFolder.isLinked()) runCatching { pushFolderExclusively() }
        return result
    }

    /** Wipes the local library and overwrites it with the server's contents (no uploads/deletions). */
    suspend fun forceFullResync(): SyncResult {
        val result = withSyncService { it.pullEverythingFromServer() }
        recordSyncSucceeded()
        if (libraryFolder.isLinked()) runCatching { pushFolderExclusively() }
        return result
    }

    /** Wipes the server's contents and overwrites them with the local library (no local deletions). */
    suspend fun forceFullResyncFromLocal(): SyncResult {
        val result = withSyncService { it.pushEverythingToServer() }
        recordSyncSucceeded()
        return result
    }

    @OptIn(ExperimentalTime::class)
    private fun recordSyncSucceeded() {
        settings.lastSyncAt = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Whether this device can sync: it holds a sync token, or a password saved by a pre-token build
     * that the next sync will trade for one (the Swift app's `hasCredentials`).
     */
    val hasSyncCredentials: Boolean
        get() = settings.syncToken.isNotEmpty() || settings.password.isNotEmpty()

    /**
     * Trades the password from the Connect This Device prompt for this device's sync token.
     *
     * The password arrives as a parameter and is never stored — it exists only for the duration of
     * this call. On success the token is stored instead, and any password a pre-token build left in
     * the vault is deleted along the way. Throws a [SyncException] with a friendly message otherwise.
     */
    suspend fun connectDevice(password: String) {
        val api = SaltyApiClient(settings.serverUrl.trimEnd('/'), tokenStore, httpEngine)
        try {
            val auth = api.login(settings.username, password, settings.deviceId, SYNC_DEVICE_NAME)
            settings.syncToken = auth.deviceToken
                // The server enrols on every login now, so a missing token means it is older than
                // this client. Failing rather than carrying on is deliberate: carrying on is exactly
                // what used to leave a client syncing with the password indefinitely, invisible on
                // the account's app list because it never had a token to show there.
                ?: throw SyncException("This server is too old for this app. Update the server, then connect again.")
            settings.password = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: Throwable) {
            throw SyncException(friendlyNetworkMessage(e))
        } finally {
            api.close()
        }
    }

    /** What [forgetDevice] managed to do, so the UI can say something useful when it fell short. */
    enum class ForgetDeviceOutcome {
        /** The token is dead server-side as well as gone from here. */
        REVOKED_ON_SERVER,

        /** The server couldn't be told, so the token may still be live there. Forgetting still
         *  happened locally — refusing to sign out because the network is down would be worse. */
        LOCAL_ONLY,
    }

    /**
     * Forgets this device: revokes its token on the server, then discards it here, so syncing stops
     * until the device is connected again.
     *
     * The revoke is attempted first, because it needs the credential this is about to destroy. Local
     * state is cleared regardless of how it goes: a user who asked to forget has forgotten, and
     * leaving them connected because a server was unreachable would be the wrong way to fail.
     */
    suspend fun forgetDevice(): ForgetDeviceOutcome {
        val token = settings.syncToken
        val outcome = if (token.isEmpty()) {
            // Never enrolled (at most a legacy saved password): there is nothing on the server to
            // revoke, so there is nothing to warn about either.
            ForgetDeviceOutcome.REVOKED_ON_SERVER
        } else {
            val api = SaltyApiClient(settings.serverUrl.trimEnd('/'), tokenStore, httpEngine)
            try {
                if (api.revokeDeviceToken(token)) ForgetDeviceOutcome.REVOKED_ON_SERVER
                else ForgetDeviceOutcome.LOCAL_ONLY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ForgetDeviceOutcome.LOCAL_ONLY
            } finally {
                api.close()
            }
        }
        settings.syncToken = ""
        settings.password = ""
        tokenStore.token = null
        return outcome
    }

    /**
     * Gets the connection authenticated with the device sync token.
     *
     * The only password this ever sees is one a pre-token build stored: it is used once to enrol
     * this device and deleted when the token comes back, so existing installs upgrade without ever
     * seeing the Connect This Device prompt. Current builds never store a password at all —
     * connecting happens through [connectDevice], with the password passing straight through.
     *
     * A rejected token (revoked from the devices page, or invalidated by a password change) says
     * plainly what to do. A network failure is NOT treated as rejection, so a flaky connection never
     * discards a token that is still perfectly good.
     */
    private suspend fun authenticate(api: SaltyApiClient) {
        val token = settings.syncToken
        if (token.isNotEmpty()) {
            if (api.loginWithDeviceToken(token) != null) return
            settings.syncToken = ""   // the server disowned it; fall through to a legacy saved password
        }

        if (settings.password.isEmpty()) {
            throw SyncException(
                if (token.isNotEmpty()) {
                    "This device is no longer authorised to sync. Use Connect This Device in Settings to sign in again."
                } else {
                    "This device is not connected to the server. Use Connect This Device in Settings."
                },
            )
        }

        val auth = api.login(settings.username, settings.password, settings.deviceId, SYNC_DEVICE_NAME)
        val issued = auth.deviceToken
            ?: throw SyncException(
                "This server is too old for this app. Update the server, then sync again.",
            )
        settings.syncToken = issued
        // Only now, once a working replacement is stored: the password stops living on this device.
        settings.password = ""
    }

    private suspend fun <T> withSyncService(block: suspend (SyncService) -> T): T = syncMutex.withLock {
        val api = SaltyApiClient(settings.serverUrl.trimEnd('/'), tokenStore, httpEngine)
        try {
            authenticate(api)
            return block(
                SyncService(
                    api, localStore, settings.deviceId, deviceName = SYNC_DEVICE_NAME,
                    // Persist the full image, then cache a 300px thumbnail blob in the DB (like the Swift app's GRDB).
                    imageSink = { recipeId, filename, bytes, imageDate ->
                        imageFiles.save(filename, bytes)
                        localStore.setRecipeImage(recipeId, filename, makeThumbnail(bytes, THUMBNAIL_MAX_PX), imageDate)
                    },
                    imageSource = { _, filename -> imageFiles.load(filename) },
                    // Lets sync re-encode an image the server can't serve as-is (a HEIC from the Swift app's
                    // bundle) instead of uploading it labelled as something it isn't.
                    imageConverter = { bytes -> convertImageToJpeg(bytes) },
                    onProgress = { _syncProgress.value = it },
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
            // In `finally` so a failed, stopped, or cancelled sync clears the progress line too — otherwise
            // the UI would keep showing whichever phase it died in.
            _syncProgress.value = null
            api.close()
        }
    }
}
