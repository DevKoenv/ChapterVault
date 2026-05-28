package dev.koenv.chaptervault.infrastructure.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

interface ThumbnailFormat {
    val extension: String
    val mimeType: String
    fun encode(bytes: ByteArray): ByteArray
}

object JpegThumbnailFormat : ThumbnailFormat {
    override val extension = "jpg"
    override val mimeType = "image/jpeg"
    override fun encode(bytes: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }
}
