package dev.koenv.chaptervault.infrastructure.notifications

import com.sun.net.httpserver.HttpServer
import dev.koenv.chaptervault.infrastructure.network.createHttpClient
import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each channel posts to a real local HTTP server through the same OkHttp client the server uses. */
class NotificationChannelDeliveryTest {
    private class Received(
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val clientPort: Int = 0,
        val atMs: Long = 0,
    )

    private val received = CopyOnWriteArrayList<Received>()
    private val startedAt = System.currentTimeMillis()

    // says what arrived when a test expected exactly one request, so a duplicate can be told apart from a retry
    private fun only(): Received {
        if (received.size != 1) {
            val seen = received.joinToString { "${it.path} from port ${it.clientPort} after ${it.atMs} ms (${it.body.length} bytes)" }
            throw AssertionError("expected one request, got ${received.size}: $seen")
        }
        return received.single()
    }

    private var status = 200
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
                received +=
                    Received(
                        exchange.requestURI.path,
                        headers,
                        body,
                        exchange.remoteAddress.port,
                        System.currentTimeMillis() - startedAt,
                    )
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"
    private val client = createHttpClient()

    @AfterEach
    fun stop() {
        server.stop(0)
        client.close()
    }

    private fun event(
        title: String,
        chapters: List<String> = listOf("Chapter 1"),
    ) = NotificationEvent(
        seriesId = "11111111-1111-1111-1111-111111111111",
        seriesTitle = title,
        newChapters = chapters.mapIndexed { i, t -> NotificationEvent.ChapterSummary("id-$i", t, i + 1.0) },
    )

    private fun send(
        channel: NotificationChannel,
        path: String,
        event: NotificationEvent,
        token: String? = null,
    ) = runBlocking { channel.send("$baseUrl$path", token, event) }

    // kotlinx is lenient about raw control characters inside strings; Discord and most receivers are not
    private fun json(body: String): JsonObject {
        assertTrue(body.none { it.code < 0x20 }, "raw control character in JSON body")
        return Json.parseToJsonElement(body).jsonObject
    }

    // a title with every character that breaks hand-built JSON, including a control character
    private val awkward = "He said \"hi\" \\ back\nline\u0008   end"

    @Test
    fun `ntfy delivers a title that is not ASCII`() {
        send(NtfyNotificationChannel(client), "/topic", event("進撃の巨人", listOf("第1話", "第2話")), token = "secret")

        val request = only()
        val title = request.headers.getValue("title")
        assertTrue(title.startsWith("=?UTF-8?B?"), "non-ASCII must be sent RFC 2047 encoded, was: $title")
        val decoded = String(Base64.getDecoder().decode(title.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8)
        assertEquals("New chapters: 進撃の巨人", decoded)
        assertEquals("2 new chapter(s): 第1話, 第2話", request.body)
        assertEquals("Bearer secret", request.headers["authorization"])
    }

    @Test
    fun `ntfy sends an ASCII title unchanged`() {
        send(NtfyNotificationChannel(client), "/topic", event("One Piece"))

        assertEquals("New chapters: One Piece", only().headers["title"])
    }

    @Test
    fun `ntfy cannot be tricked into adding headers through the title`() {
        send(NtfyNotificationChannel(client), "/topic", event("Evil\r\nX-Injected: yes"))

        assertTrue(received.isNotEmpty())
        assertTrue(received.none { it.headers.containsKey("x-injected") })
    }

    @Test
    fun `gotify sends valid JSON whatever the title contains`() {
        send(GotifyNotificationChannel(client), "", event(awkward, listOf(awkward)), token = "key")

        val request = only()
        assertEquals("/message", request.path)
        assertEquals("key", request.headers["x-gotify-key"])
        val body = json(request.body)
        assertEquals("New chapters: $awkward", body.getValue("title").jsonPrimitive.content)
        assertEquals(awkward, body.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `webhook sends valid JSON whatever the title contains`() {
        send(WebhookNotificationChannel(client), "/hook", event(awkward, listOf(awkward, "plain")), token = "tok")

        val request = only()
        assertEquals("Bearer tok", request.headers["authorization"])
        val body = json(request.body)
        assertEquals("new_chapters", body.getValue("event").jsonPrimitive.content)
        assertEquals(awkward, body.getValue("seriesTitle").jsonPrimitive.content)
        val chapters = body.getValue("newChapters").jsonArray
        assertEquals(
            listOf(awkward, "plain"),
            chapters.map {
                it.jsonObject
                    .getValue("title")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            2.0,
            chapters[1]
                .jsonObject
                .getValue("index")
                .jsonPrimitive.content
                .toDouble(),
        )
    }

    @Test
    fun `discord sends valid JSON whatever the title contains`() {
        send(DiscordNotificationChannel(client), "/hook", event(awkward, listOf(awkward, "plain")))

        val embed =
            json(only().body)
                .getValue("embeds")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("New chapters: $awkward", embed.getValue("title").jsonPrimitive.content)
        assertEquals("- $awkward\n- plain", embed.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `discord stays within its embed limits when many chapters arrive at once`() {
        val chapters = List(400) { "Chapter number $it with a reasonably long descriptive title" }

        send(DiscordNotificationChannel(client), "/hook", event("S".repeat(500), chapters))

        val embed =
            json(only().body)
                .getValue("embeds")
                .jsonArray
                .single()
                .jsonObject
        val description = embed.getValue("description").jsonPrimitive.content
        assertTrue(description.length <= 4096, "description is ${description.length} characters")
        assertTrue(
            embed
                .getValue("title")
                .jsonPrimitive.content.length <= 256,
        )
        assertTrue(Regex("and \\d+ more").containsMatchIn(description), description.takeLast(80))
    }

    @Test
    fun `a failed delivery reports the status without leaking the target url`() {
        status = 400
        val secret = "/api/webhooks/123456/SECRET-TOKEN"

        val channels: List<Pair<NotificationChannel, String>> =
            listOf(
                NtfyNotificationChannel(client) to secret,
                GotifyNotificationChannel(client) to secret,
                DiscordNotificationChannel(client) to secret,
                WebhookNotificationChannel(client) to secret,
            )
        for ((channel, path) in channels) {
            val error = assertFailsWith<IOException> { send(channel, path, event("S")) }
            assertTrue("400" in error.message.orEmpty(), error.message)
            assertFalse("SECRET-TOKEN" in error.message.orEmpty(), "${channel.typeId}: ${error.message}")
            assertFalse("127.0.0.1" in error.message.orEmpty(), "${channel.typeId}: ${error.message}")
        }
    }
}
