package dev.chaptervault.shared.format

sealed class ChapterFormat {
    data object Cbz : ChapterFormat()
    data object Folder : ChapterFormat()

    override fun toString(): String = when (this) {
        is Cbz -> "CBZ"
        is Folder -> "FOLDER"
    }

    companion object {
        fun fromString(value: String): ChapterFormat = when (value.uppercase()) {
            "CBZ" -> Cbz
            "FOLDER" -> Folder
            else -> throw IllegalArgumentException("Unknown chapter format: $value")
        }
    }
}
