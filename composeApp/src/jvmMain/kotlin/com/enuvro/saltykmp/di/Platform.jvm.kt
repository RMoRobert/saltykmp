package com.enuvro.saltykmp.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_IMAGES_DIR
import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import com.enuvro.saltykmp.db.createAppDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.util.prefs.Preferences

// The DB and images live together in a SaltyRecipeLibrary.saltyRecipeLibrary bundle, mirroring the
// Swift app's library folder so a desktop user can point both apps at the same directory. The parent
// is the user-chosen "library location" (Settings) if set, else the default app data dir.
private fun saltyLibraryDir(): File {
    val custom = createKeyValueStore().getString("libraryPath", "")
    val parent = if (custom.isNotBlank()) File(custom) else File(System.getProperty("user.home"), ".salty")
    return File(parent, SALTY_LIBRARY_DIR).apply { mkdirs() }
}

actual fun createDatabase(): AppDatabase =
    createAppDatabase(File(saltyLibraryDir(), SALTY_DB_FILE).absolutePath)

actual val customLibraryLocationSupported: Boolean = true

actual fun currentLibraryDir(): String = saltyLibraryDir().absolutePath

// Desktop uses a live custom location (above), so the copy-based linked-folder model isn't offered here.
actual val linkedFolderSyncSupported: Boolean = false
actual fun localLibraryDbPath(): String? = File(saltyLibraryDir(), SALTY_DB_FILE).absolutePath
actual fun localLibraryImagesDir(): String? = File(saltyLibraryDir(), SALTY_IMAGES_DIR).absolutePath

actual fun createHttpEngine(): HttpClientEngine = CIO.create()

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val prefs = Preferences.userRoot().node("com.enuvro.saltykmp")
    override fun getString(key: String, default: String): String = prefs.get(key, default)
    override fun putString(key: String, value: String) {
        prefs.put(key, value); prefs.flush()
    }
}

actual fun createImageFiles(): ImageFiles = object : ImageFiles {
    private val dir = File(saltyLibraryDir(), SALTY_IMAGES_DIR).apply { mkdirs() }
    override fun save(filename: String, bytes: ByteArray) {
        val safe = safeImageFilename(filename) ?: return
        File(dir, safe).writeBytes(bytes)
    }
    override fun load(filename: String): ByteArray? {
        val safe = safeImageFilename(filename) ?: return null
        return File(dir, safe).takeIf { it.exists() }?.readBytes()
    }
}

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun makeThumbnail(bytes: ByteArray, maxSize: Int): ByteArray? = skiaThumbnail(bytes, maxSize)

// Desktop has no camera picker; the editor hides the "Take Photo" button when this returns null.
@Composable
actual fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)? = null

/** Skia-based thumbnail (shared shape with the iOS actual): scale longest side to [maxSize], encode JPEG. */
private fun skiaThumbnail(bytes: ByteArray, maxSize: Int): ByteArray? = runCatching {
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
