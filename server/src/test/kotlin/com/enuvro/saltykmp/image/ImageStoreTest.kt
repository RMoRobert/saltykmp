package com.enuvro.saltykmp.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageStoreTest {

    private fun png(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun rejectsImageOverPixelBudget() {
        val store = ImageStore(Files.createTempDirectory("salty-imgtest-bomb"), maxPixels = 100L)
        // 40x40 = 1600 px, over the 100-px budget — the decompression-bomb guard must reject it.
        assertFailsWith<ImageTooLargeException> { store.store("bomb", png(40, 40)) }
    }

    @Test
    fun acceptsImageWithinPixelBudget() {
        val store = ImageStore(Files.createTempDirectory("salty-imgtest-ok"), maxPixels = 100_000L)
        val name = store.store("ok", png(40, 40))
        assertEquals("ok.png", name)
        assertTrue(store.exists(name))
    }

    @Test
    fun rejectionLeavesNoFileBehind() {
        val dir = Files.createTempDirectory("salty-imgtest-clean")
        val store = ImageStore(dir, maxPixels = 100L)
        assertFailsWith<ImageTooLargeException> { store.store("bomb", png(40, 40)) }
        assertTrue(!store.exists("bomb.png"), "a rejected image must not be written to disk")
    }

    // ---- the stored extension comes from the bytes, never from what a client claims ----

    private fun jpeg(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** The head of a real HEIC file: a `ftyp` box declaring the `heic` brand. */
    private fun heicHeader(): ByteArray =
        byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(16)

    @Test
    fun formatIsDetectedFromTheBytes() {
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(jpeg(8, 8)))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(png(8, 8)))
        assertEquals(ImageFormat.GIF, ImageFormat.detect("GIF89a".toByteArray() + ByteArray(8)))
        assertEquals(null, ImageFormat.detect(heicHeader()), "HEIC is not something this server can serve")
        assertEquals(null, ImageFormat.detect(ByteArray(0)))
        assertEquals(null, ImageFormat.detect("not an image at all".toByteArray()))
    }

    @Test
    fun storesUnderTheExtensionTheBytesSayNotTheOneAClientClaims() {
        val store = ImageStore(Files.createTempDirectory("salty-imgtest-sniff"), maxPixels = 100_000L)

        // A mislabelled upload used to put (say) PNG content in "<id>.jpg", which nothing downstream
        // could decode. The bytes decide now, so the name always matches the content.
        assertEquals("a.png", store.store("a", png(20, 20)))
        assertEquals("b.jpg", store.store("b", jpeg(20, 20)))
    }

    @Test
    fun refusesAFormatItCannotServe() {
        val dir = Files.createTempDirectory("salty-imgtest-heic")
        val store = ImageStore(dir, maxPixels = 100_000L)

        assertFailsWith<UnsupportedImageFormatException> { store.store("shot", heicHeader()) }
        assertFailsWith<UnsupportedImageFormatException> { store.store("junk", "hello".toByteArray()) }
        assertEquals(
            0L,
            Files.list(dir).use { it.filter { p -> !Files.isDirectory(p) }.count() },
            "a refused upload must leave nothing behind — storing it would have been unreadable anyway",
        )
    }
}
