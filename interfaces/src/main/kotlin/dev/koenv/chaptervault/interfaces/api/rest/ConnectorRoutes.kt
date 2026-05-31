package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ChapterMetadataDto
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ConnectorDto
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.SeriesMetadataDto
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.SeriesSearchResultDto
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.connectorRoutes(
    registry: ConnectorRegistry,
    libraryRead: LibraryReadApi,
) {
    get("/connectors") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val connectors = registry.all().map { ConnectorDto(it.id, it.name) }
        call.respond(HttpStatusCode.OK, connectors)
    }

    get("/connectors/{id}/search") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val id = call.parameters["id"]!!
        val connector = registry.findById(id)
        if (connector == null) {
            call.respondError(AppError.NotFound("Connector", id))
            return@get
        }
        val q = call.request.queryParameters["q"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        when (val result = connector.search(q, PageRequest(page, size.coerceIn(1, 100)))) {
            is Result.Success -> {
                val items = result.value.items
                val inLibrary =
                    when (val r = libraryRead.inLibraryExternalIds(id, items.map { it.externalId })) {
                        is Result.Success -> r.value
                        is Result.Failure -> emptySet()
                    }
                call.respond(
                    HttpStatusCode.OK,
                    PaginatedResponse(
                        items =
                            items.map {
                                SeriesSearchResultDto(
                                    it.externalId,
                                    it.title,
                                    it.coverUrl,
                                    it.description,
                                    it.externalId in inLibrary,
                                )
                            },
                        page = result.value.page,
                        size = result.value.size,
                        totalItems = result.value.totalItems,
                        totalPages = result.value.totalPages,
                        hasNext = result.value.hasNext,
                        hasPrevious = result.value.hasPrevious,
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/connectors/{id}/series/{externalId}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val id = call.parameters["id"]!!
        val connector = registry.findById(id)
        if (connector == null) {
            call.respondError(AppError.NotFound("Connector", id))
            return@get
        }
        val externalId = call.parameters["externalId"]!!
        when (val result = connector.fetchSeries(externalId)) {
            is Result.Success -> {
                val inLibrary =
                    when (val r = libraryRead.inLibraryExternalIds(id, listOf(externalId))) {
                        is Result.Success -> externalId in r.value
                        is Result.Failure -> false
                    }
                call.respond(
                    HttpStatusCode.OK,
                    SeriesMetadataDto(
                        result.value.externalId,
                        result.value.title,
                        result.value.coverUrl,
                        result.value.description,
                        inLibrary,
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/connectors/{id}/series/{externalId}/chapters") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val id = call.parameters["id"]!!
        val connector = registry.findById(id)
        if (connector == null) {
            call.respondError(AppError.NotFound("Connector", id))
            return@get
        }
        val externalId = call.parameters["externalId"]!!
        when (val result = connector.fetchChapters(externalId)) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    result.value.map { ChapterMetadataDto(it.externalId, it.title, it.chapterIndex, it.pageCount) },
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
