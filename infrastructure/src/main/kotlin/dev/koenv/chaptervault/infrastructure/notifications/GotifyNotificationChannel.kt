package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GotifyNotificationChannel(private val httpClient: HttpClient) : NotificationChannel {
    override val typeId = "GOTIFY"

    override suspend fun send(targetUrl: String, targetToken: String?, event: NotificationEvent) {
        val listText = event.newChapters.joinToString(", ") { it.title }
        httpClient.post("${targetUrl.trimEnd('/')}/message") {
            targetToken?.let { header("X-Gotify-Key", it) }
            contentType(ContentType.Application.Json)
            setBody("""{"title":"New chapters: ${j(event.seriesTitle)}","message":"${j(listText)}","priority":5}""")
        }
    }

    private fun j(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
