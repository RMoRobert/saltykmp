package com.enuvro.saltykmp.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Simple persisted key-value store (settings). */
interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}

/** Filesystem-ish storage for recipe image bytes, keyed by filename. */
interface ImageFiles {
    fun save(filename: String, bytes: ByteArray)
    fun load(filename: String): ByteArray?
}

/**
 * Reduce a (server-supplied) image [filename] to a safe basename before it touches the local filesystem:
 * take the last path segment (any separator style) and reject traversal / empty / NUL names. A malicious or
 * compromised server — or a MITM over an http:// connection — otherwise could return a name like
 * "../../shared_prefs/salty.xml" and make the app overwrite arbitrary files inside its sandbox. Mirrors the
 * server's ImageStore.safePath. Returns null when nothing safe remains (callers should skip the operation).
 */
fun safeImageFilename(filename: String): String? {
    val base = filename.substringAfterLast('/').substringAfterLast('\\')
    if (base.isEmpty() || base == "." || base == ".." || base.contains('\u0000')) return null
    return base
}

expect fun createKeyValueStore(): KeyValueStore

expect fun createImageFiles(): ImageFiles

/** Decode encoded image bytes (jpeg/png) into a Compose ImageBitmap, or null on failure. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * Resize encoded image [bytes] to a thumbnail whose longest side is at most [maxSize] px and
 * re-encode it as JPEG. Mirrors the Swift app's 300×300 thumbnail caching. Returns null on failure.
 */
expect fun makeThumbnail(bytes: ByteArray, maxSize: Int): ByteArray?

/**
 * Longest side used when re-encoding an image the server can't serve as-is (HEIC, WebP) into JPEG for
 * upload. Generous rather than exact: the server downsizes jpg/png to 1200px on receipt anyway, so this
 * only exists to keep a conversion from ballooning and to stay well under the server's megapixel guard.
 */
const val UPLOAD_JPEG_MAX_PX = 2400

/**
 * Re-encode an image as JPEG for upload, at up to [UPLOAD_JPEG_MAX_PX] on its longest side.
 *
 * Built on [makeThumbnail], whose platform actuals are already "decode, scale to fit, encode JPEG" and
 * which skip the scaling when the source is smaller than the bound. Returns null when the platform
 * can't decode the bytes — notably HEIC on desktop and iOS, whose Skia build carries no HEIF decoder;
 * Android decodes HEIC from API 28. A null means the image is skipped rather than uploaded under a
 * content type that lies about it: see `SyncImagePreparer`.
 */
fun convertImageToJpeg(bytes: ByteArray): ByteArray? = makeThumbnail(bytes, UPLOAD_JPEG_MAX_PX)

/**
 * Remembers a camera-capture launcher, available only on platforms with a camera picker (Android, iOS).
 * Returns a lambda that opens the camera, or null where unsupported (desktop). The captured image's
 * encoded bytes (or null if cancelled/denied) are delivered to [onResult].
 */
@Composable
expect fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)?

/** True where a user-chosen library folder backs the live DB (desktop). Mobile keeps its sandbox. */
expect val customLibraryLocationSupported: Boolean

/** Absolute path of the active library bundle (DB + images), for display in Settings. */
expect fun currentLibraryDir(): String

/** What a folder the user picked as a library location turned out to be. */
enum class LibraryLocationOutcome {
    /** It already holds a library bundle, which is what the next launch will open. */
    ExistingLibrary,

    /** It was empty and usable, so an empty bundle was created in it. Recipes are NOT moved there. */
    NewLibrary,

    /** Nothing could be created in it — read-only, or gone since the picker listed it. */
    Unusable,
}

/**
 * Ready a user-picked library folder and report what it was, so Settings can say whether the choice
 * adopts an existing library or starts an empty one — and can refuse a folder it cannot write to,
 * rather than storing a path that only fails at the next launch.
 *
 * Creates the bundle directory when the folder is empty; the DB and image directory inside it are
 * created on first use. Desktop only — mobile never offers this ([customLibraryLocationSupported]).
 */
expect fun prepareLibraryLocation(path: String): LibraryLocationOutcome

/**
 * True where copy-based "linked folder" library sync is offered (Android: SAF folder ↔ app storage).
 * Desktop uses a live custom location instead ([customLibraryLocationSupported]); iOS is a follow-up.
 */
expect val linkedFolderSyncSupported: Boolean

/**
 * Example cloud providers whose folders the platform's picker can actually link, for the Settings caption
 * (e.g. "Nextcloud or Google Drive"). Which providers show up is decided by the provider apps, not by us:
 * on Android a cloud app must expose folder trees through the Storage Access Framework, and OneDrive's
 * Android app only exposes single files. Empty where [linkedFolderSyncSupported] is false.
 */
expect val linkedFolderProviderExamples: String

/**
 * Optional extra caption naming a provider that can NOT be linked on this platform and the workaround
 * (Android: OneDrive via a third-party sync app). Null where there is nothing to warn about.
 */
expect val linkedFolderProviderCaveat: String?

/** Absolute path of the live SQLite DB file (its `-wal`/`-shm` sidecars sit next to it), or null if unknown. */
expect fun localLibraryDbPath(): String?

/** Absolute path of the live recipe-images directory, or null if unknown. */
expect fun localLibraryImagesDir(): String?

/**
 * What became of a file the app handed to the user, so the caller can confirm it (or not) without
 * knowing which of the two delivery models the platform uses.
 */
sealed interface ExportOutcome {
    /** Written where the user chose. [location] is a path or filename, for the confirmation message. */
    data class Saved(val location: String) : ExportOutcome

    /** Passed to the system share sheet. Where it goes next — and whether the user backs out — is not
     *  something the sheet reports, so this is as much as we can say. */
    data object Shared : ExportOutcome

    /** The user dismissed the save dialog. */
    data object Cancelled : ExportOutcome

    data class Failed(val message: String?) : ExportOutcome
}

/**
 * Hand a generated file to the user, by whichever route the platform makes idiomatic: a save dialog on
 * desktop, the system share sheet on Android and iOS — matching what the Swift app does on macOS
 * (an export panel) versus iOS (a `ShareLink`).
 *
 * [stem] is a filename WITHOUT its extension (see `RecipeExport.filenameStem`); [extension] carries no
 * leading dot. Never throws: a failure comes back as [ExportOutcome.Failed].
 */
expect suspend fun deliverExportedFile(stem: String, extension: String, bytes: ByteArray): ExportOutcome
