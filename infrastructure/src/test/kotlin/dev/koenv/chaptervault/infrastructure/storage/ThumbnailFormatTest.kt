package dev.koenv.chaptervault.infrastructure.storage

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    private fun transparentPng(): ByteArray {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0x80FF0000.toInt())
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    // signature + IHDR only: enough for a decoder to report the size, nowhere near enough to allocate it
    private fun pngHeaderClaiming(
        width: Int,
        height: Int,
    ): ByteArray {
        val ihdr =
            ByteBuffer
                .allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(2)
                .put(0)
                .put(0)
                .put(0)
                .array()
        val type = "IHDR".toByteArray()
        val crc =
            CRC32()
                .also {
                    it.update(type)
                    it.update(ihdr)
                }.value
                .toInt()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteBuffer.allocate(4).putInt(13).array() + type + ihdr + ByteBuffer.allocate(4).putInt(crc).array()
    }

    @Test
    fun `a PNG with transparency becomes a non-empty JPEG`() {
        val result = JpegThumbnailFormat.encode(transparentPng())

        assertTrue(result.isNotEmpty(), "ImageIO writes nothing for an alpha image unless it is flattened first")
        assertNotNull(ImageIO.read(ByteArrayInputStream(result)))
    }

    @Test
    fun `bytes that are not an image are rejected`() {
        assertFailsWith<IllegalArgumentException> { JpegThumbnailFormat.encode("<html><script>alert(1)</script></html>".toByteArray()) }
    }

    @Test
    fun `an image that claims absurd dimensions is rejected before it is decoded`() {
        assertFailsWith<IllegalArgumentException> { JpegThumbnailFormat.encode(pngHeaderClaiming(60_000, 60_000)) }
    }

    @Test
    fun `a supported image without a decoder is kept as it is`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".toByteArray()

        assertContentEquals(webp, JpegThumbnailFormat.encode(webp))
    }
}
