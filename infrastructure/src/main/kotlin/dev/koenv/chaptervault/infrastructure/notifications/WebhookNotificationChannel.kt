package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class WebhookNotificationChannel(private val httpClient: HttpClient) : NotificationChannel {
    override val typeId = "WEBHOOK"

    override suspend fun send(targetUrl: String, targetToken: String?, event: NotificationEvent) {
        val chaptersJson = event.newChapters.joinToString(",") {
            """{"id":"${it.id}","title":"${j(it.title)}","index":${it.index}}"""
        }
        httpClient.post(targetUrl) {
            contentType(ContentType.Application.Json)
            targetToken?.let { tok -> header("Authorization", "Bearer $tok") }
            setBody("""{"event":"new_chapters","seriesId":"${event.seriesId}","seriesTitle":"${j(event.seriesTitle)}","newChapters":[$chaptersJson]}""")
        }
    }

    private fun j(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
