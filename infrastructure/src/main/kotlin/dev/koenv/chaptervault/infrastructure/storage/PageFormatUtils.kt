package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page

object PageFormatUtils {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    private const val JPEG = "image/jpeg"
    private const val PNG = "image/png"
    private const val WEBP = "image/webp"

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private const val WEBP_MARKER_OFFSET = 8
    private val WEBP_MARKER = "WEBP".toByteArray(Charsets.US_ASCII)
    private val RIFF_MARKER = "RIFF".toByteArray(Charsets.US_ASCII)

    fun mimeTypeFor(filename: String): String =
        when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> JPEG
            "png" -> PNG
            "webp" -> WEBP
            else -> "application/octet-stream"
        }

    /** The image type [bytes] really are, from their magic bytes, or null when it is none of the supported ones. */
    fun sniffMimeType(bytes: ByteArray): String? =
        when {
            bytes.startsWith(PNG_SIGNATURE) -> PNG
            bytes.startsWith(JPEG_SIGNATURE) -> JPEG
            bytes.startsWith(RIFF_MARKER) && bytes.startsWith(WEBP_MARKER, WEBP_MARKER_OFFSET) -> WEBP
            else -> null
        }

    /** File extension for [page]: what its bytes are, else what the connector declared, else jpg. */
    fun extensionFor(page: Page): String =
        when (sniffMimeType(page.data) ?: page.mimeType) {
            PNG -> "png"
            WEBP -> "webp"
            else -> "jpg"
        }

    fun buildPageIndex(filenames: List<String>): List<String> =
        filenames
            .filter { it.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }

    private fun ByteArray.startsWith(
        prefix: ByteArray,
        offset: Int = 0,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }
}
