package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.BackupProgress
import dev.koenv.chaptervault.kernel.api.BackupReadingStatus
import dev.koenv.chaptervault.kernel.api.LibraryBackup
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BackupProgressDto(
    val connectorId: String,
    val externalId: String,
    val latestChapterExternalId: String?,
    val latestPage: Int,
    val readAt: String,
    val language: String = "en",
)

@Serializable
data class BackupReadingStatusDto(
    val connectorId: String,
    val externalId: String,
    val status: String,
    val language: String = "en",
)

@Serializable
data class LibraryBackupDto(
    val version: Int = 1,
    val exportedAt: String,
    val progress: List<BackupProgressDto>,
    val readingStatuses: List<BackupReadingStatusDto>,
    // kept for backward compat when importing old backup files; ignored on import
    val overrides: List<JsonElement> = emptyList(),
)

@Serializable
data class BackupImportSummaryDto(
    val progressRestored: Int,
    val progressSkipped: Int,
    val statusesRestored: Int,
    val statusesSkipped: Int,
)

fun Route.backupRoutes(backupApi: BackupApi) {
    get("/backup/export") {
        val principal = call.requirePermission(Permission.READ_LIBRARY) ?: return@get
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
                                BackupProgressDto(
                                    it.connectorId,
                                    it.externalId,
                                    it.latestChapterExternalId,
                                    it.latestPage,
                                    it.readAt,
                                    it.language,
                                )
                            },
                        readingStatuses =
                            backup.readingStatuses.map {
                                BackupReadingStatusDto(
                                    it.connectorId,
                                    it.externalId,
                                    it.status,
                                    it.language,
                                )
                            },
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/backup/import") {
        val principal = call.requirePermission(Permission.WRITE_PROGRESS) ?: return@post
        val dto = call.receiveOrBadRequest<LibraryBackupDto>() ?: return@post
        val backup =
            LibraryBackup(
                exportedAt = dto.exportedAt,
                progress =
                    dto.progress.map {
                        BackupProgress(it.connectorId, it.externalId, it.latestChapterExternalId, it.latestPage, it.readAt, it.language)
                    },
                readingStatuses =
                    dto.readingStatuses.map {
                        BackupReadingStatus(it.connectorId, it.externalId, it.status, it.language)
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
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
