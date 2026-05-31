package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.notifications.DefaultNotificationChannelRegistry
import dev.koenv.chaptervault.infrastructure.notifications.NtfyNotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class NotificationChannelRegistryTest {
    @Test
    fun `registry dispatches to correct channel by typeId`() {
        val captured = mutableListOf<String>()
        val engine = MockEngine { request ->
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
}
