package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
private data class SetReadingStatusRequest(val status: String)

fun Route.readingStatusRoutes(readingStatusApi: ReadingStatusApi) {
    put("/library/series/{id}/status") {
        val principal = call.principal<KtorPrincipal>()!!
        val seriesId = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respondBadRequest("Invalid series ID"); return@put
        }
        val request = try { call.receive<SetReadingStatusRequest>() } catch (e: Exception) {
            call.respondBadRequest("Invalid request body"); return@put
        }
        val status = runCatching { ReadingStatus.valueOf(request.status) }.getOrNull()
            ?: run {
                call.respondBadRequest(
                    "Invalid status '${request.status}'. Valid values: ${ReadingStatus.entries.joinToString { it.name }}"
                )
                return@put
            }
        readingStatusApi.setStatus(principal.user.id, seriesId, status)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/library/series/{id}/status") {
        val principal = call.principal<KtorPrincipal>()!!
        val seriesId = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respondBadRequest("Invalid series ID"); return@delete
        }
        readingStatusApi.clearStatus(principal.user.id, seriesId)
        call.respond(HttpStatusCode.NoContent)
    }
}
