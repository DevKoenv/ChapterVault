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
import java.io.IOException

class DiscordNotificationChannel(
    private val httpClient: HttpClient,
) : NotificationChannel {
    override val typeId = "DISCORD"

    override suspend fun send(
        targetUrl: String,
        targetToken: String?,
        event: NotificationEvent,
    ) {
        // "\\n" produces the two-char escape sequence \n in the JSON string value,
        // which Discord renders as a newline in embed descriptions.
        val bullets = event.newChapters.joinToString("\\n") { "- ${jsonEscape(it.title)}" }
        val response: HttpResponse =
            httpClient.post(targetUrl) {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"embeds":[{"title":"New chapters: ${jsonEscape(event.seriesTitle)}","description":"$bullets","color":5814783}]}""",
                )
            }
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value} from $targetUrl")
        }
    }
}
