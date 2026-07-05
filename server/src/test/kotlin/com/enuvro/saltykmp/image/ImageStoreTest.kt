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
        assertFailsWith<ImageTooLargeException> { store.store("bomb", png(40, 40), "png") }
    }

    @Test
    fun acceptsImageWithinPixelBudget() {
        val store = ImageStore(Files.createTempDirectory("salty-imgtest-ok"), maxPixels = 100_000L)
        val name = store.store("ok", png(40, 40), "png")
        assertEquals("ok.png", name)
        assertTrue(store.exists(name))
    }

    @Test
    fun rejectionLeavesNoFileBehind() {
        val dir = Files.createTempDirectory("salty-imgtest-clean")
        val store = ImageStore(dir, maxPixels = 100L)
        assertFailsWith<ImageTooLargeException> { store.store("bomb", png(40, 40), "png") }
        assertTrue(!store.exists("bomb.png"), "a rejected image must not be written to disk")
    }
}
