package dev.koenv.chaptervault.kernel.extension

interface NotificationChannel {
    val typeId: String

    suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    )
}

data class NotificationEvent(
    val seriesId: String,
    val seriesTitle: String,
    val newChapters: List<ChapterSummary>,
) {
    data class ChapterSummary(
        val id: String,
        val title: String,
        val index: Double,
    )
}
