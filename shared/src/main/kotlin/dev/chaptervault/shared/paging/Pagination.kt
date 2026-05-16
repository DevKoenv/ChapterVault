package dev.chaptervault.shared.paging

data class PageRequest(
    val page: Int = 0,
    val size: Int = 20,
) {
    init {
        require(page >= 0) { "Page must be >= 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }
    }
}

data class Pagination<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    val totalPages: Int get() = if (totalItems == 0L) 0 else ((totalItems - 1) / size + 1).toInt()
    val hasNext: Boolean get() = page < totalPages - 1
    val hasPrevious: Boolean get() = page > 0
}
