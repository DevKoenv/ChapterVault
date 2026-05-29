package dev.koenv.chaptervault.infrastructure.storage

object PageFormatUtils {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun mimeTypeFor(filename: String): String =
        when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }

    fun buildPageIndex(filenames: List<String>): List<String> =
        filenames
            .filter { it.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }
}
