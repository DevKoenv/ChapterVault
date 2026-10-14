package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import kotlinx.serialization.SerializationException

/**
 * The authenticated principal of this call. Answers 401 and returns null when there is none, so the caller can
 * `?: return@route`. Routes sit behind `authenticate`, so a missing principal means the credentials were not accepted,
 * not that the user lacks permission.
 */
suspend fun ApplicationCall.requirePrincipal(): KtorPrincipal? {
    val principal = principal<KtorPrincipal>()
    if (principal == null) respondError(AppError.Unauthorized())
    return principal
}

/**
 * Like [requirePrincipal], and also answers 403 when the user's roles do not grant [permission]. This is the one place
 * routes decide who may do what: they ask for a permission, never for a particular role.
 */
suspend fun ApplicationCall.requirePermission(permission: Permission): KtorPrincipal? {
    val principal = requirePrincipal() ?: return null
    if (!principal.user.hasPermission(permission)) {
        respondForbidden()
        return null
    }
    return principal
}

/** Requires [permission] for every route registered inside this one, e.g. a whole `authenticate` block. */
fun Route.requirePermissionForAll(permission: Permission) {
    intercept(ApplicationCallPipeline.Call) {
        if (call.requirePermission(permission) == null) finish()
    }
}

/** The path parameter [name] as an [Id]; answers 400 "Invalid [label] ID" and returns null when it is not one. */
suspend fun ApplicationCall.idParam(
    name: String,
    label: String,
): Id? =
    try {
        Id.from(parameters[name] ?: error("missing path parameter $name"))
    } catch (_: IllegalArgumentException) {
        respondBadRequest("Invalid $label ID")
        null
    } catch (_: IllegalStateException) {
        respondBadRequest("Invalid $label ID")
        null
    }

/**
 * `page` and `size` from the query string, clamped into range instead of rejected. A negative page or an oversized
 * page is a client mistake, not a server error.
 */
fun ApplicationCall.pageRequest(
    defaultSize: Int,
    maxSize: Int = DEFAULT_MAX_PAGE_SIZE,
): PageRequest {
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 0
    val size = request.queryParameters["size"]?.toIntOrNull() ?: defaultSize
    return PageRequest(page.coerceAtLeast(0), size.coerceIn(1, maxSize))
}

private const val DEFAULT_MAX_PAGE_SIZE = 100

/**
 * The request body as [T]. Answers 400 and returns null when the body is missing, malformed or does not match. Only
 * parse failures are caught: anything else (a cancelled call, a body that is too large) must propagate.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T? =
    try {
        receive<T>()
    } catch (_: ContentTransformationException) {
        respondBadRequest("Invalid request body")
        null
    } catch (_: SerializationException) {
        respondBadRequest("Invalid request body")
        null
    } catch (_: BadRequestException) {
        respondBadRequest("Invalid request body")
        null
    }
