package com.enuvro.saltykmp.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_IMAGES_DIR
import com.enuvro.saltykmp.db.createAppDatabase
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.launch
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

actual fun createDatabase(): AppDatabase = createAppDatabase()

// iOS keeps the library in the app's Documents sandbox. A user-picked folder would need
// security-scoped bookmarks + DB access scoping (see TODO); the Settings UI shows this read-only.
actual val customLibraryLocationSupported: Boolean = false

@OptIn(ExperimentalForeignApi::class)
actual fun currentLibraryDir(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

// Copy-based linked-folder sync (same model as Android). The live DB stays in app storage; the user links
// a folder (iCloud Drive/OneDrive/Nextcloud via the document picker) that we copy to/from. FileKit handles
// the iOS security-scoped bookmark + folder picker.
actual val linkedFolderSyncSupported: Boolean = true

// Matches where SQLiter's NativeSqliteDriver actually puts the DB by default — File(<AppSupport>/databases,
// name) — so we reference the EXISTING file (no driver change, no data move). Images live in Documents
// (see createImageFiles). The copy engine handles the two locations independently.
actual fun localLibraryDbPath(): String? {
    val appSupport = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .first() as String
    return "$appSupport/databases/$SALTY_DB_FILE"
}

actual fun localLibraryImagesDir(): String? {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    return "$docs/$SALTY_IMAGES_DIR"
}

actual fun createHttpEngine(): HttpClientEngine = Darwin.create()

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun getString(key: String, default: String): String = defaults.stringForKey(key) ?: default
    override fun putString(key: String, value: String) {
        defaults.setObject(value, key)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun createImageFiles(): ImageFiles = object : ImageFiles {
    private val dir: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val path = "$docs/$SALTY_IMAGES_DIR"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        path
    }

    override fun save(filename: String, bytes: ByteArray) {
        val safe = safeImageFilename(filename) ?: return
        bytes.toNSData().writeToFile("$dir/$safe", true)
    }

    override fun load(filename: String): ByteArray? {
        val safe = safeImageFilename(filename) ?: return null
        return NSData.dataWithContentsOfFile("$dir/$safe")?.toByteArray()
    }
}

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** Skia-based thumbnail (shared shape with the JVM actual): scale longest side to [maxSize], encode JPEG. */
actual fun makeThumbnail(bytes: ByteArray, maxSize: Int): ByteArray? = runCatching {
    val src = SkiaImage.makeFromEncoded(bytes)
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return null
    val scale = maxSize.toFloat() / maxOf(w, h)
    if (scale >= 1f) return src.encodeToData(EncodedImageFormat.JPEG, 80)?.bytes // already small
    val tw = maxOf(1, (w * scale).toInt())
    val th = maxOf(1, (h * scale).toInt())
    val surface = Surface.makeRasterN32Premul(tw, th)
    surface.canvas.drawImageRect(src, Rect.makeWH(tw.toFloat(), th.toFloat()))
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)?.bytes
}.getOrNull()

@Composable
actual fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)? {
    val scope = rememberCoroutineScope()
    val launcher = rememberCameraPickerLauncher { file: PlatformFile? ->
        scope.launch { onResult(file?.readBytes()) }
    }
    return { launcher.launch() }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
