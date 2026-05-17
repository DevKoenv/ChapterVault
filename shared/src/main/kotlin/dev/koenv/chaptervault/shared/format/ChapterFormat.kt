package dev.koenv.chaptervault.shared.format

sealed class ChapterFormat {
    data object Cbz : ChapterFormat() {
        override fun toString() = "CBZ"
    }
    data object Folder : ChapterFormat() {
        override fun toString() = "FOLDER"
    }

    companion object {
        fun fromString(value: String): ChapterFormat = when (value.uppercase()) {
            "CBZ" -> Cbz
            "FOLDER" -> Folder
            else -> throw IllegalArgumentException("Unknown chapter format: $value")
        }
    }
}
