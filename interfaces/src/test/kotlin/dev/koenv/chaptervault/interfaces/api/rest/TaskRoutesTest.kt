package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQuery
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TaskRoutesTest {
    private val fakeTask =
        Task(
            id = Id.from("00000000-0000-0000-0000-000000000001"),
            type = TaskType.FETCH_SERIES_METADATA,
            status = TaskStatus.PENDING,
            targetType = TargetType.SERIES,
            targetId = Id.from("00000000-0000-0000-0000-000000000002"),
            payload = mapOf("connectorId" to "mock"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun testApp(
        system: SystemApi,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        when (cred.token) {
                            "admin-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN)))
                            "user-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    taskRoutes(system)
                }
            }
        }
        block()
    }

    @Test
    fun `GET tasks returns 200 with paginated list`() {
        testApp(
            system =
                fakeSystem(
                    listTasksResult = Result.Success(Pagination(listOf(fakeTask), 0, 20, 1L)),
                ),
        ) {
            val response =
                client.get("/tasks") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "FETCH_SERIES_METADATA")
            assertContains(body, "PENDING")
            assertContains(body, "totalItems")
        }
    }

    @Test
    fun `GET tasks by id returns 200`() {
        testApp(
            system =
                fakeSystem(
                    getTaskResult = Result.Success(fakeTask),
                ),
        ) {
            val response =
                client.get("/tasks/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "FETCH_SERIES_METADATA")
        }
    }

    @Test
    fun `GET tasks by id returns 404 for unknown id`() {
        testApp(
            system =
                fakeSystem(
                    getTaskResult = Result.Failure(AppError.NotFound("Task", "00000000-0000-0000-0000-000000000099")),
                ),
        ) {
            val response =
                client.get("/tasks/00000000-0000-0000-0000-000000000099") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST tasks cancel returns 204 for ADMIN`() {
        testApp(
            system =
                fakeSystem(
                    cancelResult = Result.Success(Unit),
                ),
        ) {
            val response =
                client.post("/tasks/00000000-0000-0000-0000-000000000001/cancel") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `GET tasks passes status, type and target filters on, ignoring case`() {
        val seen = mutableListOf<TaskQuery>()
        val target = "00000000-0000-0000-0000-000000000002"
        testApp(system = fakeSystem(seen = seen)) {
            listOf("/tasks", "/tasks?status=failed", "/tasks?type=download_chapter&targetId=$target&status=FAILED").forEach { path ->
                assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth("admin-token") }.status, path)
            }
        }
        assertEquals(
            listOf(
                TaskQuery(),
                TaskQuery(status = TaskStatus.FAILED),
                TaskQuery(status = TaskStatus.FAILED, type = TaskType.DOWNLOAD_CHAPTER, targetId = Id.from(target)),
            ),
            seen,
        )
    }

    @Test
    fun `GET tasks rejects an unknown status, type or target id`() {
        val seen = mutableListOf<TaskQuery>()
        testApp(system = fakeSystem(seen = seen)) {
            listOf("status=DONE", "type=UPLOAD", "targetId=not-a-uuid").forEach { param ->
                assertEquals(HttpStatusCode.BadRequest, client.get("/tasks?$param") { bearerAuth("admin-token") }.status, param)
            }
        }
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `GET tasks returns 403 for USER role`() {
        testApp(system = fakeSystem()) {
            val response = client.get("/tasks") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `GET tasks by id returns 403 for USER role`() {
        testApp(system = fakeSystem()) {
            val response = client.get("/tasks/00000000-0000-0000-0000-000000000001") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `POST tasks cancel returns 403 for USER`() {
        testApp(
            system = fakeSystem(),
        ) {
            val response =
                client.post("/tasks/00000000-0000-0000-0000-000000000001/cancel") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }
}

private fun fakeSystem(
    listTasksResult: Result<Pagination<Task>> = Result.Success(Pagination(listOf(), 0, 20, 0L)),
    getTaskResult: Result<Task> = Result.Failure(AppError.NotFound("Task", "unknown")),
    cancelResult: Result<Unit> = Result.Success(Unit),
    seen: MutableList<TaskQuery> = mutableListOf(),
): SystemApi =
    object : SystemApi {
        override suspend fun listTasks(
            query: TaskQuery,
            request: PageRequest,
        ): Result<Pagination<Task>> {
            seen += query
            return listTasksResult
        }

        override suspend fun getTask(id: Id) = getTaskResult

        override suspend fun cancelTask(id: Id) = cancelResult

        override fun listExtensions(): List<ExtensionEntry> = emptyList()
    }
