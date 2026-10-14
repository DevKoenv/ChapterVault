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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class WebhookNotificationChannel(
    private val httpClient: HttpClient,
) : NotificationChannel {
    override val typeId = "WEBHOOK"

    override suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    ) {
        val body =
            buildJsonObject {
                put("event", "new_chapters")
                put("seriesId", event.seriesId)
                put("seriesTitle", event.seriesTitle)
                putJsonArray("newChapters") {
                    event.newChapters.forEach { chapter ->
                        addJsonObject {
                            put("id", chapter.id)
                            put("title", chapter.title)
                            put("index", chapter.index)
                        }
                    }
                }
            }
        val response: HttpResponse =
            httpClient.post(targetUrl) {
                contentType(ContentType.Application.Json)
                targetToken?.let { tok -> header("Authorization", "Bearer $tok") }
                setBody(body.toString())
            }
        if (!response.status.isSuccess()) throw deliveryFailure(response.status.value)
    }
}
