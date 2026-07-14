package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.LibraryBackup
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class BackupProgressDto(
    val connectorId: String,
    val externalId: String,
    val latestChapterExternalId: String?,
    val latestPage: Int,
    val readAt: String,
)

@Serializable
data class BackupReadingStatusDto(
    val connectorId: String,
    val externalId: String,
    val status: String,
)

@Serializable
data class BackupOverrideDto(
    val connectorId: String,
    val externalId: String,
    val field: String,
    val value: String,
)

@Serializable
data class LibraryBackupDto(
    val version: Int = 1,
    val exportedAt: String,
    val progress: List<BackupProgressDto>,
    val readingStatuses: List<BackupReadingStatusDto>,
    val overrides: List<BackupOverrideDto>,
)

@Serializable
data class BackupImportSummaryDto(
    val progressRestored: Int,
    val progressSkipped: Int,
    val statusesRestored: Int,
    val statusesSkipped: Int,
    val overridesRestored: Int,
    val overridesSkipped: Int,
)

fun Route.backupRoutes(backupApi: BackupApi) {
    get("/backup/export") {
        val principal =
            call.principal<KtorPrincipal>() ?: run {
                call.respondForbidden()
                return@get
            }
        val now =
            java.time.Instant
                .now()
                .toString()
        when (val result = backupApi.exportBackup(principal.user.id, now)) {
            is Result.Success -> {
                val backup = result.value
                call.respond(
                    HttpStatusCode.OK,
                    LibraryBackupDto(
                        exportedAt = backup.exportedAt,
                        progress =
                            backup.progress.map {
                                BackupProgressDto(it.connectorId, it.externalId, it.latestChapterExternalId, it.latestPage, it.readAt)
                            },
                        readingStatuses = backup.readingStatuses.map { BackupReadingStatusDto(it.connectorId, it.externalId, it.status) },
                        overrides = backup.overrides.map { BackupOverrideDto(it.connectorId, it.externalId, it.field, it.value) },
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/backup/import") {
        val principal =
            call.principal<KtorPrincipal>() ?: run {
                call.respondForbidden()
                return@post
            }
        val dto = call.receive<LibraryBackupDto>()
        val backup =
            LibraryBackup(
                exportedAt = dto.exportedAt,
                progress =
                    dto.progress.map {
                        dev.koenv.chaptervault.kernel.api.BackupProgress(
                            it.connectorId,
                            it.externalId,
                            it.latestChapterExternalId,
                            it.latestPage,
                            it.readAt,
                        )
                    },
                readingStatuses =
                    dto.readingStatuses.map {
                        dev.koenv.chaptervault.kernel.api
                            .BackupReadingStatus(it.connectorId, it.externalId, it.status)
                    },
                overrides =
                    dto.overrides.map {
                        dev.koenv.chaptervault.kernel.api.BackupOverride(
                            it.connectorId,
                            it.externalId,
                            it.field,
                            it.value,
                        )
                    },
            )
        when (val result = backupApi.importBackup(principal.user.id, backup)) {
            is Result.Success -> {
                val s = result.value
                call.respond(
                    HttpStatusCode.OK,
                    BackupImportSummaryDto(
                        progressRestored = s.progressRestored,
                        progressSkipped = s.progressSkipped,
                        statusesRestored = s.statusesRestored,
                        statusesSkipped = s.statusesSkipped,
                        overridesRestored = s.overridesRestored,
                        overridesSkipped = s.overridesSkipped,
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
