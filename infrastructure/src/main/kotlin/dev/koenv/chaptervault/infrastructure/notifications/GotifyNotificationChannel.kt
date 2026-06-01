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

class GotifyNotificationChannel(
    private val httpClient: HttpClient,
) : NotificationChannel {
    override val typeId = "GOTIFY"

    override suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    ) {
        val listText = event.newChapters.joinToString(", ") { it.title }
        val url = "${targetUrl.trimEnd('/')}/message"
        val response: HttpResponse =
            httpClient.post(url) {
                targetToken?.let { header("X-Gotify-Key", it) }
                contentType(ContentType.Application.Json)
                setBody("""{"title":"New chapters: ${jsonEscape(event.seriesTitle)}","message":"${jsonEscape(listText)}","priority":5}""")
            }
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value} from $url")
        }
    }
}
