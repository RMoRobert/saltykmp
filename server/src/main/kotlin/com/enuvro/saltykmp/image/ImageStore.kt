package com.enuvro.saltykmp.image

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Thrown by [ImageStore.store] when an image's pixel dimensions exceed [ImageStore.maxPixels]. */
class ImageTooLargeException(message: String) : RuntimeException(message)

/**
 * Thrown by [ImageStore.store] for bytes that are not one of the formats this server can serve.
 *
 * The server resizes and thumbnails with ImageIO, which handles JPEG, PNG and GIF and nothing else. A
 * format it can't read used to be stored anyway: the resize silently no-opped, the thumbnail endpoint
 * 404'd forever, and — because [ImageStore.imagePixelCount] also can't read it — the decompression-bomb
 * guard didn't apply either. Refusing at the door is both safer and honest to the client.
 */
class UnsupportedImageFormatException(message: String) : RuntimeException(message)

/**
 * Thrown by [ImageStore.store] when the recipe id it was handed cannot name a file inside the store.
 * The routes reject such an id first (see `isSafeId`); this is the storage layer refusing to be the
 * only thing standing between a client-supplied string and `Files.write`.
 */
class InvalidImageNameException(message: String) : RuntimeException(message)

/** An image format the server is prepared to store, and the extension it is stored under. */
enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    GIF("gif"),
    ;

    companion object {
        /**
         * Identifies the format from the bytes themselves, or null for anything else.
         *
         * The declared Content-Type is deliberately NOT consulted. It used to pick the stored extension,
         * which meant any client that mislabelled an upload — a stale build guessing from a filename, or
         * a hand-rolled request — made the server write (say) HEIC content to `<id>.jpg`. Nothing
         * downstream recovers from that, and every client then fails to decode it. The bytes are the
         * only trustworthy source, so they are what the extension is derived from.
         */
        fun detect(bytes: ByteArray): ImageFormat? = when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
            bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> GIF // "GIF8"
            else -> null
        }

        private fun ByteArray.startsWith(vararg expected: Int): Boolean =
            size >= expected.size && expected.withIndex().all { (i, b) -> this[i] == b.toByte() }
    }
}

/**
 * Filesystem-backed recipe image storage; files are named by recipe id + extension. [maxPixels] caps the
 * decoded resolution (width×height) accepted by [store], rejecting decompression bombs before any full decode.
 */
class ImageStore(private val baseDir: Path, private val maxPixels: Long = DEFAULT_MAX_PIXELS) {

    private val thumbDir: Path = baseDir.resolve(".thumbs")

    init {
        Files.createDirectories(baseDir)
    }

    /**
     * Stores an image for [recipeId] and returns the filename it was stored under.
     *
     * The extension comes from the BYTES, never from a caller-supplied hint — see [ImageFormat.detect].
     * Anything that isn't JPEG, PNG or GIF is refused rather than stored unreadably.
     */
    fun store(recipeId: String, bytes: ByteArray): String {
        val format = ImageFormat.detect(bytes)
            ?: throw UnsupportedImageFormatException(
                "Unsupported image format. The server stores JPEG, PNG and GIF; convert the image first.",
            )

        // Reject decompression bombs up front: a small compressed file can declare enormous dimensions that
        // balloon to gigabytes once decoded/resized. Read the dimensions from the header (no pixel decode)
        // and refuse anything over the pixel budget. Now that only ImageIO-readable formats get this far, a
        // null here means a truncated or corrupt file rather than a format we simply couldn't measure.
        val pixels = imagePixelCount(bytes)
        if (pixels != null && pixels > maxPixels) {
            throw ImageTooLargeException("Image resolution ${pixels / 1_000_000}MP exceeds the ${maxPixels / 1_000_000}MP limit")
        }

        val filename = "$recipeId.${format.extension}"
        // Through safePath, exactly as load/delete/exists go: the id is client-supplied, and the one
        // write path used to resolve it against baseDir directly. An id of `../../tmp/x` -- which
        // reaches a handler already decoded, because the router splits on the raw `/` and decodes each
        // segment afterwards -- wrote attacker bytes outside the image directory, and an id of
        // `./<someone-elses-recipe>` overwrote their photo in place without touching their row.
        val target = safePath(filename)
            ?: throw InvalidImageNameException("A recipe id cannot name a path: $recipeId")
        val processedBytes = if (format == ImageFormat.GIF) bytes else resizeImage(bytes, format.extension, 1200)
        Files.write(target, processedBytes)
        deleteThumb(filename) // invalidate any cached thumbnail for this name
        return filename
    }

    /**
     * Returns a small (≤[maxDimension]px) JPEG thumbnail for [filename], generated on demand from the
     * stored full image and cached on disk. The cache is keyed by name and invalidated by comparing
     * modification times, so an in-place image replacement regenerates it. Returns null if the source
     * image is missing or can't be decoded.
     */
    fun loadThumbnail(filename: String, maxDimension: Int = THUMB_SIZE): ByteArray? {
        val src = safePath(filename) ?: return null
        if (!Files.exists(src)) return null
        val thumbPath = thumbPathFor(filename)
        if (Files.exists(thumbPath) &&
            Files.getLastModifiedTime(thumbPath) >= Files.getLastModifiedTime(src)
        ) {
            return runCatching { Files.readAllBytes(thumbPath) }.getOrNull()
        }
        val thumb = makeThumbnail(Files.readAllBytes(src), maxDimension) ?: return null
        runCatching {
            Files.createDirectories(thumbDir)
            Files.write(thumbPath, thumb)
        }
        return thumb
    }

    private fun makeThumbnail(bytes: ByteArray, maxDimension: Int): ByteArray? = try {
        val input = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val longest = maxOf(input.width, input.height)
        if (longest <= 0) return null
        val scale = if (longest > maxDimension) maxDimension.toDouble() / longest else 1.0
        val w = (input.width * scale).toInt().coerceAtLeast(1)
        val h = (input.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(input, 0, 0, w, h, null)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
    } catch (e: Exception) {
        null
    }

    private fun thumbPathFor(filename: String): Path {
        val base = Paths.get(filename).fileName.toString().substringBeforeLast('.')
        return thumbDir.resolve("$base.jpg")
    }

    private fun deleteThumb(filename: String) {
        runCatching { Files.deleteIfExists(thumbPathFor(filename)) }
    }

    private fun resizeImage(bytes: ByteArray, extension: String, maxDimension: Int): ByteArray {
        try {
            val input = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            if (input.width <= maxDimension && input.height <= maxDimension) return bytes

            val aspectRatio = input.width.toDouble() / input.height.toDouble()
            val (targetWidth, targetHeight) = if (input.width > input.height) {
                maxDimension to (maxDimension / aspectRatio).toInt()
            } else {
                (maxDimension * aspectRatio).toInt() to maxDimension
            }

            val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(input, 0, 0, targetWidth, targetHeight, null)
            g.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(resized, extension, baos)
            return baos.toByteArray()
        } catch (e: Exception) {
            return bytes
        }
    }

    fun load(filename: String): ByteArray? {
        val path = safePath(filename) ?: return null
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    fun delete(filename: String) {
        safePath(filename)?.let { Files.deleteIfExists(it) }
        deleteThumb(filename)
    }

    fun exists(filename: String): Boolean = safePath(filename)?.let { Files.exists(it) } ?: false

    /**
     * Pixel count (width×height) read from the image header without decoding the pixels, or null when the
     * format is unknown/unreadable. Cheap and bomb-safe: `getWidth`/`getHeight` parse only the header.
     */
    private fun imagePixelCount(bytes: ByteArray): Long? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.setInput(iis)
                reader.getWidth(reader.minIndex).toLong() * reader.getHeight(reader.minIndex).toLong()
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /**
     * Resolve a filename to a path inside baseDir, or null for anything that is not one of this
     * store's own files.
     *
     * Comparing against `fileName` alone was not enough. `Paths.get("").fileName` is `""` and
     * `Paths.get("..").fileName` is `".."`, so both compared equal to what was passed in and resolved
     * to the image directory itself or its parent -- which is how a stored `imageFilename` of `""`
     * turned a later image delete into `deleteIfExists(baseDir)`. A stored name is `<id>.<ext>`, so
     * that is what is required: one segment, no leading dot (which also excludes the thumbnail
     * cache), and an extension.
     */
    private fun safePath(filename: String): Path? {
        val name = Paths.get(filename).fileName?.toString() ?: return null
        if (name != filename || !STORED_NAME.matches(name)) return null
        return baseDir.resolve(name)
    }

    companion object {
        /**
         * What a file in this store is called: `<recipeId>.<ext>`, one path segment, no leading dot.
         * The extension is not pinned to the three formats [ImageFormat] writes today, because a
         * deployment predating that rule can hold a name an older build chose.
         */
        private val STORED_NAME = Regex("^[A-Za-z0-9_~:@+-][A-Za-z0-9._~:@+-]{0,119}\\.[A-Za-z0-9]{1,8}$")

        /** Longest-side pixel size for generated list thumbnails (matches the clients' 300px caches). */
        const val THUMB_SIZE = 300

        /**
         * Default cap on accepted image resolution (width×height). 100 MP comfortably covers real camera/phone
         * photos while blocking decompression bombs (which declare far larger dimensions). Override with the
         * `SALTY_MAX_IMAGE_PIXELS` env var. Note the 25 MB upload cap bounds *compressed* size; this bounds the
         * *decoded* size, which is what actually drives memory use during resize/thumbnailing.
         */
        const val DEFAULT_MAX_PIXELS = 100_000_000L
    }
}
