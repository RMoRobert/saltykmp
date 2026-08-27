package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.LibraryClassifierEditor
import com.enuvro.saltykmp.db.LibraryDuplicateMerger
import com.enuvro.saltykmp.di.ImageFiles
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.SECRET_KEY_PASSWORD
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
import kotlin.time.Duration.Companion.seconds

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
    var password: String
        get() = secrets.get(SECRET_KEY_PASSWORD).orEmpty()
        set(value) {
            if (value.isEmpty()) secrets.clear(SECRET_KEY_PASSWORD)
            else secrets.put(SECRET_KEY_PASSWORD, value, account = username)
        }

    /** Where [password] is kept, for the Settings caption, e.g. "Windows Credential Manager". */
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

/** Quiet period after the last edit before the library is copied to the linked folder. */
private val FOLDER_PUSH_DEBOUNCE = 45.seconds

/** Manual DI container — holds the database, HTTP engine, repositories, and builds a SyncService. */
class AppModule {
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

    /** App-lifetime scope for background work (debounced auto-sync). Lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)

    /**
     * What the running sync is doing, or null while idle. Set from whichever coroutine is syncing — a
     * MutableStateFlow is safe to write from any thread, and Compose collects it on the main one.
     *
     * Shared by manual and automatic syncs. Nothing serialises those two, so if they ever overlap this
     * shows whichever phase reported last — cosmetic, and strictly less of a problem than the overlap
     * itself, which predates this flow.
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
        val result = runCatching { libraryFolder.pushOut() }.getOrElse { LibraryFolderSyncResult.ERROR }
        println("LibraryFolderLink background push: $result")
    }

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
