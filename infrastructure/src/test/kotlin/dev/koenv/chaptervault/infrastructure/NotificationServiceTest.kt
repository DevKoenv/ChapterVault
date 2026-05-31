package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationServiceTest {
    private fun mockTarget(
        type: String,
        url: String = "https://example.com",
    ) = NotificationTarget(
        id = Id.generate(),
        name = "Test",
        type = type,
        url = url,
        token = null,
        enabled = true,
        createdAt = Instant.now(),
    )

    private fun buildService(
        target: NotificationTarget?,
        recordedRequests: MutableList<String> = mutableListOf(),
    ): NotificationDispatchApi {
        val api =
            object : NotificationApi {
                override suspend fun listTargets() = if (target != null) listOf(target) else emptyList()

                override suspend fun findTarget(id: Id): Result<NotificationTarget> =
                    target?.takeIf { it.id == id }?.let { Result.Success(it) }
                        ?: Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))

                override suspend fun createTarget(input: NotificationTargetInput) = Result.Success(mockTarget("WEBHOOK"))

                override suspend fun updateTarget(
                    id: Id,
                    patch: NotificationTargetPatch,
                ): Result<NotificationTarget> = Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))

                override suspend fun deleteTarget(id: Id) = Result.Success(Unit)
            }
        val engine =
            MockEngine { request ->
                recordedRequests.add("${request.method.value} ${request.url}")
                respond("", HttpStatusCode.OK)
            }
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }
        return NotificationService(
            eventBus = InMemoryEventBus(),
            notificationApi = api,
            httpClient = client,
        )
    }

    @Test
    fun `sendTest returns Success for enabled NTFY target`() =
        runBlocking {
            val target = mockTarget("NTFY", "https://ntfy.sh/test")
            val service = buildService(target)
            assertIs<Result.Success<Unit>>(service.sendTest(target.id))
        }

    @Test
    fun `sendTest returns Failure for unknown target id`() =
        runBlocking {
            val service = buildService(null)
            assertIs<Result.Failure>(service.sendTest(Id.generate()))
        }

    @Test
    fun `sendTest fires HTTP request to target url`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val target = mockTarget("WEBHOOK", "https://example.com/hook")
            val service = buildService(target, requests)
            service.sendTest(target.id)
            assertTrue(requests.any { it.contains("example.com") })
        }
}
