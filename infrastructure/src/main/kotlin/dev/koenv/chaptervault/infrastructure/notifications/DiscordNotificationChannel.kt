package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class DiscordNotificationChannel(private val httpClient: HttpClient) : NotificationChannel {
    override val typeId = "DISCORD"

    override suspend fun send(targetUrl: String, targetToken: String?, event: NotificationEvent) {
        val bullets = event.newChapters.joinToString("\\n") { "- ${j(it.title)}" }
        httpClient.post(targetUrl) {
            contentType(ContentType.Application.Json)
            setBody("""{"embeds":[{"title":"New chapters: ${j(event.seriesTitle)}","description":"$bullets","color":5814783}]}""")
        }
    }

    private fun j(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
