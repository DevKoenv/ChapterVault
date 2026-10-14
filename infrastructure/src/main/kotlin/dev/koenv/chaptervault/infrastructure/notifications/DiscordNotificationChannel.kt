package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
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

class DiscordNotificationChannel(
    private val httpClient: HttpClient,
) : NotificationChannel {
    override val typeId = "DISCORD"

    override suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    ) {
        val body =
            buildJsonObject {
                putJsonArray("embeds") {
                    addJsonObject {
                        put("title", discordTitle(event.seriesTitle))
                        put("description", discordDescription(event.newChapters.map { it.title }))
                        put("color", EMBED_COLOR)
                    }
                }
            }
        val response: HttpResponse =
            httpClient.post(targetUrl) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        if (!response.status.isSuccess()) throw deliveryFailure(response.status.value)
    }

    private companion object {
        const val EMBED_COLOR = 5814783
    }
}
