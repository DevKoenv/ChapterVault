package dev.koenv.chaptervault.kernel.library

data class Page(
    val index: Int,
    val data: ByteArray,
    val mimeType: String = "image/jpeg",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Page) return false
        return index == other.index && data.contentEquals(other.data) && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
