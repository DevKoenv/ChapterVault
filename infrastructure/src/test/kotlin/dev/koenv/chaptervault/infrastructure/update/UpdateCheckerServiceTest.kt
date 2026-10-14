package dev.koenv.chaptervault.infrastructure.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerServiceTest {
    private fun release(tag: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/DevKoenv/ChapterVault/releases/tag/$tag","name":"x"}"""

    private fun service(
        current: String,
        requests: AtomicInteger = AtomicInteger(),
        respond: () -> Pair<HttpStatusCode, String>,
    ): UpdateCheckerService {
        val engine =
            MockEngine {
                requests.incrementAndGet()
                val (status, body) = respond()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return UpdateCheckerService(client, current)
    }

    private fun UpdateCheckerService.checked(): UpdateCheckerService = also { runBlocking { it.checkNow() } }

    @Test
    fun `a newer release is reported with its link`() {
        val info = service("1.0.0") { HttpStatusCode.OK to release("v1.1.0") }.checked().getUpdateInfo()

        assertTrue(info.updateAvailable)
        assertEquals("1.1.0", info.latestVersion)
        assertEquals("https://github.com/DevKoenv/ChapterVault/releases/tag/v1.1.0", info.releaseUrl)
        assertEquals("1.0.0", info.currentVersion)
    }

    @Test
    fun `the same or an older release is not an update`() {
        assertFalse(service("1.1.0") { HttpStatusCode.OK to release("v1.1.0") }.checked().getUpdateInfo().updateAvailable)
        assertFalse(service("1.2.0") { HttpStatusCode.OK to release("v1.1.9") }.checked().getUpdateInfo().updateAvailable)
    }

    @Test
    fun `versions are compared by number, not by text`() {
        assertTrue(service("1.9.0") { HttpStatusCode.OK to release("v1.10.0") }.checked().getUpdateInfo().updateAvailable)
        assertTrue(service("1.0") { HttpStatusCode.OK to release("v1.0.1") }.checked().getUpdateInfo().updateAvailable)
        assertFalse(service("1.0.0") { HttpStatusCode.OK to release("v1.0") }.checked().getUpdateInfo().updateAvailable)
    }

    @Test
    fun `a pre-release is older than the release it leads to`() {
        assertTrue(service("1.0.0-rc1") { HttpStatusCode.OK to release("v1.0.0") }.checked().getUpdateInfo().updateAvailable)
        assertFalse(service("1.0.0") { HttpStatusCode.OK to release("v1.0.0-rc1") }.checked().getUpdateInfo().updateAvailable)
        assertTrue(service("1.0.0-rc1") { HttpStatusCode.OK to release("v1.0.1") }.checked().getUpdateInfo().updateAvailable)
    }

    @Test
    fun `before any check there is no latest version`() {
        val info = service("1.0.0") { HttpStatusCode.OK to release("v9.9.9") }.getUpdateInfo()

        assertNull(info.latestVersion)
        assertFalse(info.updateAvailable)
    }

    @Test
    fun `an error response leaves the state as it was`() {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val info = service("1.0.0") { status to "{}" }.checked().getUpdateInfo()

            assertNull(info.latestVersion, status.toString())
            assertFalse(info.updateAvailable)
        }
    }

    @Test
    fun `an unreadable response leaves the state as it was`() {
        assertNull(service("1.0.0") { HttpStatusCode.OK to "<html>rate limited</html>" }.checked().getUpdateInfo().latestVersion)
        assertNull(service("1.0.0") { HttpStatusCode.OK to """{"unexpected":true}""" }.checked().getUpdateInfo().latestVersion)
    }

    @Test
    fun `a failed check keeps the last good result`() {
        var fail = false
        val checker = service("1.0.0") { if (fail) HttpStatusCode.InternalServerError to "" else HttpStatusCode.OK to release("v1.5.0") }
        checker.checked()
        fail = true
        checker.checked()

        assertEquals("1.5.0", checker.getUpdateInfo().latestVersion)
    }

    @Test
    fun `no connection is not an error`() {
        val client = HttpClient(MockEngine { throw IOException("offline") })

        val info = UpdateCheckerService(client, "1.0.0").checked().getUpdateInfo()

        assertNull(info.latestVersion)
    }

    @Test
    fun `the check repeats so a long running server does not go stale`() =
        runBlocking<Unit> {
            val requests = AtomicInteger()
            val checker = service("1.0.0", requests) { HttpStatusCode.OK to release("v1.0.0") }

            checker.startAsync(recheckEveryMs = 50)

            withTimeout(5_000) { while (requests.get() < 3) delay(20) }
            checker.stop()
        }
}
