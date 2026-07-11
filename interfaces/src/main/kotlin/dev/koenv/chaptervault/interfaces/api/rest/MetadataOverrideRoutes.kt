package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.MetadataOverrideApi
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class OverrideValueRequest(
    val value: String,
)

fun Route.metadataOverrideRoutes(overrideApi: MetadataOverrideApi) {
    get("/library/series/{id}/overrides") {
        val seriesId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@get
            }
        when (val result = overrideApi.getOverrides(seriesId)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    put("/library/series/{id}/overrides/{field}") {
        val seriesId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@put
            }
        val field =
            call.parameters["field"] ?: run {
                call.respondBadRequest("Missing field name")
                return@put
            }
        val value = call.receiveText().trim()
        if (value.isBlank()) {
            call.respondBadRequest("Value must not be blank")
            return@put
        }
        when (val result = overrideApi.setOverride(seriesId, field, value)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/series/{id}/overrides/{field}") {
        val seriesId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@delete
            }
        val field =
            call.parameters["field"] ?: run {
                call.respondBadRequest("Missing field name")
                return@delete
            }
        when (val result = overrideApi.clearOverride(seriesId, field)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
