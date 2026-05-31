package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException

class WebhookNotificationChannel(private val httpClient: HttpClient) : NotificationChannel {
    override val typeId = "WEBHOOK"

    override suspend fun send(targetUrl: String, targetToken: String?, event: NotificationEvent) {
        val chaptersJson = event.newChapters.joinToString(",") {
            """{"id":"${it.id}","title":"${jsonEscape(it.title)}","index":${it.index}}"""
        }
        val response: HttpResponse = httpClient.post(targetUrl) {
            contentType(ContentType.Application.Json)
            targetToken?.let { tok -> header("Authorization", "Bearer $tok") }
            setBody("""{"event":"new_chapters","seriesId":"${event.seriesId}","seriesTitle":"${jsonEscape(event.seriesTitle)}","newChapters":[$chaptersJson]}""")
        }
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value} from $targetUrl")
        }
    }
}
