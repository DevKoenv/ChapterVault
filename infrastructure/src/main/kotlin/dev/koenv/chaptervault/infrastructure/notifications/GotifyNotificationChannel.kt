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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val body =
            buildJsonObject {
                put("title", "New chapters: ${event.seriesTitle}")
                put("message", listText)
                put("priority", GOTIFY_PRIORITY)
            }
        val response: HttpResponse =
            httpClient.post(url) {
                targetToken?.let { header("X-Gotify-Key", it) }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        if (!response.status.isSuccess()) throw deliveryFailure(response.status.value)
    }

    private companion object {
        const val GOTIFY_PRIORITY = 5
    }
}
