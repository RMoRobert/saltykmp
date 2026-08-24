package com.enuvro.saltykmp.sync

/**
 * Re-encodes an image the platform can decode as JPEG. Supplied by the app, because decoding needs a
 * platform codec and this module has none. Returns null when the bytes can't be decoded here — which is
 * the normal answer for HEIC on desktop, where Skia carries no HEIF decoder.
 */
fun interface ImageToJpegConverter {
    fun toJpeg(bytes: ByteArray): ByteArray?
}

/** Image bytes ready to upload, with the content type the server will file them under. */
class PreparedSyncImage(val bytes: ByteArray, val contentType: String, val extension: String)

/**
 * Gets local image bytes into a shape the server can serve and every client can render — the Kotlin
 * counterpart of the Swift app's `SyncImagePreparer`.
 *
 * The format is read from the BYTES, never from the filename. That is the whole point: the Swift app
 * stores camera images in the shared bundle as `<id>.heic`, and the server derives the extension it
 * stores under from the uploaded part's Content-Type. Labelling HEIC bytes `image/jpeg` — which is what
 * an extension-based guess did for anything that wasn't png/gif — made the server write HEIC content to
 * `<id>.jpg`. Nothing downstream recovers from that: the server's ImageIO can't decode it, so its
 * resize silently no-ops and its thumbnail endpoint 404s, and every client that isn't Android gets
 * bytes its decoder rejects.
 *
 * JPEG, PNG and GIF go up as they are. Anything else is converted to JPEG by the app's converter, and
 * if that fails the image is NOT uploaded: a missing image is an honest state the next sync retries,
 * while a mislabelled one is a broken image on every device, permanently.
 */
object SyncImagePreparer {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"
    const val HEIC = "image/heic"

    /** The media type the leading bytes identify, or null for anything unrecognised. */
    fun detectContentType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> GIF // "GIF8"
        // "RIFF"..."WEBP"
        bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes.matchesAt(8, 0x57, 0x45, 0x42, 0x50) -> WEBP
        // ISO base media file: an 'ftyp' box at offset 4. HEIC/HEIF/AVIF all live here.
        bytes.size >= 12 && bytes.matchesAt(4, 0x66, 0x74, 0x79, 0x70) -> HEIC
        else -> null
    }

    /**
     * Prepares [bytes] for upload. Null means "don't upload": the format can't be served as-is and no
     * converter could re-encode it.
     */
    fun prepare(bytes: ByteArray, converter: ImageToJpegConverter?): PreparedSyncImage? {
        if (bytes.isEmpty()) return null

        when (detectContentType(bytes)) {
            JPEG -> return PreparedSyncImage(bytes, JPEG, "jpg")
            PNG -> return PreparedSyncImage(bytes, PNG, "png")
            GIF -> return PreparedSyncImage(bytes, GIF, "gif")
        }

        val jpeg = runCatching { converter?.toJpeg(bytes) }.getOrNull()
        return if (jpeg != null && jpeg.isNotEmpty()) PreparedSyncImage(jpeg, JPEG, "jpg") else null
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean = matchesAt(0, *expected)

    private fun ByteArray.matchesAt(offset: Int, vararg expected: Int): Boolean {
        if (size < offset + expected.size) return false
        return expected.withIndex().all { (i, b) -> this[offset + i] == b.toByte() }
    }
}
