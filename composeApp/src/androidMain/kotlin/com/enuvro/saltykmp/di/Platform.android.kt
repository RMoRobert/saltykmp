package com.enuvro.saltykmp.di

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_IMAGES_DIR
import com.enuvro.saltykmp.db.createAppDatabase
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.launch

/** Set from MainActivity before the Compose content is created. */
@SuppressLint("StaticFieldLeak")
lateinit var androidAppContext: Context

actual fun createDatabase(): AppDatabase = createAppDatabase(androidAppContext)

// Android is sandboxed; a user-picked folder (SAF tree URI) can't back a live SQLite DB, so the
// library stays in app storage. The Settings UI shows this read-only.
actual val customLibraryLocationSupported: Boolean = false

actual fun currentLibraryDir(): String = androidAppContext.filesDir.absolutePath

// Linked-folder (copy-based) sync: the live DB stays in app storage; the user links a SAF folder
// (OneDrive/Nextcloud/…) that we copy to/from. Live SQLite can't run on a SAF document, so this is the
// only workable model on Android (see TODO.md / LibraryFolderLink).
actual val linkedFolderSyncSupported: Boolean = true

actual fun localLibraryDbPath(): String? = androidAppContext.getDatabasePath(SALTY_DB_FILE).absolutePath

actual fun localLibraryImagesDir(): String? = File(androidAppContext.filesDir, SALTY_IMAGES_DIR).absolutePath

actual fun createHttpEngine(): HttpClientEngine = Android.create()

actual fun createKeyValueStore(): KeyValueStore = object : KeyValueStore {
    private val sp = androidAppContext.getSharedPreferences("salty", Context.MODE_PRIVATE)
    override fun getString(key: String, default: String): String = sp.getString(key, default) ?: default
    override fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }
}

actual fun createImageFiles(): ImageFiles = object : ImageFiles {
    private val dir = File(androidAppContext.filesDir, SALTY_IMAGES_DIR).apply { mkdirs() }
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
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()

actual fun makeThumbnail(bytes: ByteArray, maxSize: Int): ByteArray? = runCatching {
    // Cheap downscale-on-decode, then exact scale to fit the longest side within maxSize.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= maxSize) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
        decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true,
    )
    ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}.getOrNull()

@Composable
actual fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)? {
    val scope = rememberCoroutineScope()
    val launcher = rememberCameraPickerLauncher { file: PlatformFile? ->
        scope.launch { onResult(file?.readBytes()) }
    }
    return { launcher.launch() }
}
