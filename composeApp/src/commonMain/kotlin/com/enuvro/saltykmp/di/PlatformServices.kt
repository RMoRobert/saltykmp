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

/**
 * True where copy-based "linked folder" library sync is offered (Android: SAF folder ↔ app storage).
 * Desktop uses a live custom location instead ([customLibraryLocationSupported]); iOS is a follow-up.
 */
expect val linkedFolderSyncSupported: Boolean

/** Absolute path of the live SQLite DB file (its `-wal`/`-shm` sidecars sit next to it), or null if unknown. */
expect fun localLibraryDbPath(): String?

/** Absolute path of the live recipe-images directory, or null if unknown. */
expect fun localLibraryImagesDir(): String?
