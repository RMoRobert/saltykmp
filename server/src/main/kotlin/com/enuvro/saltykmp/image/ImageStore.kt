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
 * Filesystem-backed recipe image storage; files are named by recipe id + extension. [maxPixels] caps the
 * decoded resolution (width×height) accepted by [store], rejecting decompression bombs before any full decode.
 */
class ImageStore(private val baseDir: Path, private val maxPixels: Long = DEFAULT_MAX_PIXELS) {

    private val thumbDir: Path = baseDir.resolve(".thumbs")

    init {
        Files.createDirectories(baseDir)
    }

    fun store(recipeId: String, bytes: ByteArray, extension: String): String {
        // Reject decompression bombs up front: a small compressed file can declare enormous dimensions that
        // balloon to gigabytes once decoded/resized. Read the dimensions from the header (no pixel decode)
        // and refuse anything over the pixel budget. Unknown/unreadable formats return null and are stored
        // as-is — they're never decoded server-side except by ImageIO, which is itself bounded here.
        val pixels = imagePixelCount(bytes)
        if (pixels != null && pixels > maxPixels) {
            throw ImageTooLargeException("Image resolution ${pixels / 1_000_000}MP exceeds the ${maxPixels / 1_000_000}MP limit")
        }
        val filename = "$recipeId.${extension.trimStart('.')}"
        val processedBytes = if (extension.lowercase().let { it == "jpg" || it == "jpeg" || it == "png" }) {
            resizeImage(bytes, extension, 1200)
        } else bytes
        Files.write(baseDir.resolve(filename), processedBytes)
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

    /** Resolve a filename to a path inside baseDir, rejecting path traversal. */
    private fun safePath(filename: String): Path? {
        val name = Paths.get(filename).fileName?.toString() ?: return null
        if (name != filename) return null
        return baseDir.resolve(name)
    }

    companion object {
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
