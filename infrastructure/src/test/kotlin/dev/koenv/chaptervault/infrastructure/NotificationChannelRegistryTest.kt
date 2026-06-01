package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.notifications.DefaultNotificationChannelRegistry
import dev.koenv.chaptervault.infrastructure.notifications.DiscordNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.GotifyNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.NtfyNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.WebhookNotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationChannelRegistryTest {
    @Test
    fun `registry dispatches to correct channel by typeId`() {
        val captured = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                captured.add(request.url.toString())
                respond("", HttpStatusCode.OK)
            }
        val client = HttpClient(engine)
        val registry = DefaultNotificationChannelRegistry()
        registry.register(NtfyNotificationChannel(client))
        val channel = registry.find("NTFY")
        assertNotNull(channel)
        runBlocking {
            channel!!.send(
                targetUrl = "https://ntfy.sh/test",
                targetToken = null,
                event = NotificationEvent("s1", "Manga", emptyList()),
            )
        }
        assertEquals(1, captured.size)
    }

    @Test
    fun `GotifyNotificationChannel posts to message endpoint with X-Gotify-Key`() {
        val captured = mutableListOf<Pair<String, String?>>()
        val engine =
            MockEngine { request ->
                captured.add(request.url.toString() to request.headers["X-Gotify-Key"])
                respond("", HttpStatusCode.OK)
            }
        val client = HttpClient(engine)
        runBlocking {
            GotifyNotificationChannel(client).send(
                targetUrl = "https://gotify.example.com",
                targetToken = "mytoken",
                event = NotificationEvent("s1", "My Manga", listOf(NotificationEvent.ChapterSummary("c1", "Ch 1", 1.0))),
            )
        }
        assertEquals(1, captured.size)
        assertEquals("https://gotify.example.com/message", captured[0].first)
        assertEquals("mytoken", captured[0].second)
    }

    @Test
    fun `DiscordNotificationChannel posts embeds JSON`() {
        val bodies = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                bodies.add(request.body.toByteArray().toString(Charsets.UTF_8))
                respond("", HttpStatusCode.OK)
            }
        val client = HttpClient(engine)
        runBlocking {
            DiscordNotificationChannel(client).send(
                targetUrl = "https://discord.com/api/webhooks/test",
                targetToken = null,
                event = NotificationEvent("s1", "My Manga", listOf(NotificationEvent.ChapterSummary("c1", "Ch 1", 1.0))),
            )
        }
        assertEquals(1, bodies.size)
        assertTrue(bodies[0].contains("embeds"))
        assertTrue(bodies[0].contains("My Manga"))
    }

    @Test
    fun `WebhookNotificationChannel posts structured JSON`() {
        val bodies = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                bodies.add(request.body.toByteArray().toString(Charsets.UTF_8))
                respond("", HttpStatusCode.OK)
            }
        val client = HttpClient(engine)
        runBlocking {
            WebhookNotificationChannel(client).send(
                targetUrl = "https://webhook.example.com",
                targetToken = null,
                event = NotificationEvent("s1", "My Manga", listOf(NotificationEvent.ChapterSummary("c1", "Ch 1", 1.0))),
            )
        }
        assertEquals(1, bodies.size)
        assertTrue(bodies[0].contains("new_chapters"))
        assertTrue(bodies[0].contains("My Manga"))
    }
}
