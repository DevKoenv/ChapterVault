package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class NtfyNotificationChannel(private val httpClient: HttpClient) : NotificationChannel {
    override val typeId = "NTFY"

    override suspend fun send(targetUrl: String, targetToken: String?, event: NotificationEvent) {
        val count = event.newChapters.size
        val listText = event.newChapters.joinToString(", ") { it.title }
        httpClient.post(targetUrl) {
            header("Title", "New chapters: ${event.seriesTitle}")
            targetToken?.let { header("Authorization", "Bearer $it") }
            setBody("$count new chapter(s): $listText")
        }
    }
}
