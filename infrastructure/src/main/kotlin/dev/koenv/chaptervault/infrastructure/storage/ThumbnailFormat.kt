package dev.koenv.chaptervault.infrastructure.storage

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

interface ThumbnailFormat {
    val extension: String
    val mimeType: String

    /** @throws IllegalArgumentException when [bytes] are not an image that can safely be stored as a thumbnail */
    fun encode(bytes: ByteArray): ByteArray
}

object JpegThumbnailFormat : ThumbnailFormat {
    // ~50 megapixels: bigger than any real cover, and about 200 MB once decoded
    private const val MAX_PIXELS = 50_000_000L

    override val extension = "jpg"
    override val mimeType = "image/jpeg"

    override fun encode(bytes: ByteArray): ByteArray {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
        if (reader == null) {
            input?.close()
            // no decoder for this format (WebP, say): keep it only if it really is one of the image types we serve
            require(PageFormatUtils.sniffMimeType(bytes) != null) { "Cover is not a supported image" }
            return bytes
        }
        try {
            reader.input = input
            val pixels = reader.getWidth(0).toLong() * reader.getHeight(0)
            require(pixels in 1..MAX_PIXELS) { "Cover dimensions are not acceptable" }
            val out = ByteArrayOutputStream()
            check(ImageIO.write(flattened(reader.read(0)), "jpg", out)) { "No JPEG encoder available" }
            return out.toByteArray()
        } finally {
            reader.dispose()
            input.close()
        }
    }

    // the JPEG writer produces nothing for images with an alpha channel, so draw them on white first
    private fun flattened(image: BufferedImage): BufferedImage {
        if (!image.colorModel.hasAlpha()) return image
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return rgb
    }
}
