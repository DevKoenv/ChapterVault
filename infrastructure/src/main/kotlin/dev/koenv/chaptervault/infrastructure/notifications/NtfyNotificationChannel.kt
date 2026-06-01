package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import java.io.IOException

class NtfyNotificationChannel(
    private val httpClient: HttpClient,
) : NotificationChannel {
    override val typeId = "NTFY"

    override suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    ) {
        val count = event.newChapters.size
        val listText = event.newChapters.joinToString(", ") { it.title }
        val response: HttpResponse =
            httpClient.post(targetUrl) {
                header("Title", "New chapters: ${event.seriesTitle.replace(Regex("[\r\n]"), " ")}")
                targetToken?.let { header("Authorization", "Bearer $it") }
                setBody("$count new chapter(s): $listText")
            }
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value} from $targetUrl")
        }
    }
}
