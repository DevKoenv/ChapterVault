package dev.koenv.chaptervault.infrastructure.storage

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class ThumbnailFormatTest {
    private fun pngBytes(): ByteArray {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFF0000)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `JpegThumbnailFormat has extension jpg and mimeType image-jpeg`() {
        assertEquals("jpg", JpegThumbnailFormat.extension)
        assertEquals("image/jpeg", JpegThumbnailFormat.mimeType)
    }

    @Test
    fun `JpegThumbnailFormat encodes image bytes to JPEG`() {
        val result = JpegThumbnailFormat.encode(pngBytes())
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
        assertEquals(0xFF.toByte(), result[2])
    }
}
