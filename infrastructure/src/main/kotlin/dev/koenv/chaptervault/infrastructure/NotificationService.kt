package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationType
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.NewChaptersDiscovered
import dev.koenv.chaptervault.kernel.event.on
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

class NotificationService(
    private val eventBus: EventBus,
    private val notificationApi: NotificationApi,
    private val httpClient: HttpClient,
) : NotificationDispatchApi {

    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    fun start() {
        eventBus.on<NewChaptersDiscovered> { event ->
            dispatch(event)
        }
        log.info("NotificationService started")
    }

    private data class ChapterInfo(val id: String, val title: String, val index: Double)

    private suspend fun dispatch(event: NewChaptersDiscovered) {
        if (event.chapters.isEmpty()) return
        val targets = notificationApi.listTargets().filter { it.enabled }
        if (targets.isEmpty()) return
        val chapters = event.chapters.map { ChapterInfo(it.id.toString(), it.title, it.chapterIndex) }
        targets.forEach { target ->
            runCatching {
                sendToTarget(target, event.series.id.toString(), event.series.title, chapters)
            }.onFailure { e ->
                log.warn("Notification to '${target.name}' (${target.type}) failed: ${e.message}")
            }
        }
    }

    override suspend fun sendTest(targetId: Id): Result<Unit> {
        val target = when (val r = notificationApi.findTarget(targetId)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        return runCatching {
            sendToTarget(
                target,
                seriesId = "00000000-0000-0000-0000-000000000000",
                seriesTitle = "ChapterVault Test",
                chapters = listOf(ChapterInfo("00000000-0000-0000-0000-000000000001", "Test Chapter 1", 1.0)),
            )
            Result.Success(Unit)
        }.getOrElse { e ->
            Result.Failure(AppError.InternalError("Test notification failed: ${e.message}"))
        }
    }

    private suspend fun sendToTarget(
        target: NotificationTarget,
        seriesId: String,
        seriesTitle: String,
        chapters: List<ChapterInfo>,
    ) {
        val count = chapters.size
        val listText = chapters.joinToString(", ") { it.title }
        when (target.type) {
            NotificationType.NTFY -> {
                httpClient.post(target.url) {
                    header("Title", "New chapters: $seriesTitle")
                    target.token?.let { header("Authorization", "Bearer $it") }
                    setBody("$count new chapter(s): $listText")
                }
            }
            NotificationType.GOTIFY -> {
                httpClient.post("${target.url.trimEnd('/')}/message") {
                    target.token?.let { header("X-Gotify-Key", it) }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"title":"New chapters: ${j(seriesTitle)}","message":"${j(listText)}","priority":5}"""
                    )
                }
            }
            NotificationType.DISCORD -> {
                val bullets = chapters.joinToString("\\n") { "- ${j(it.title)}" }
                httpClient.post(target.url) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"embeds":[{"title":"New chapters: ${j(seriesTitle)}","description":"$bullets","color":5814783}]}"""
                    )
                }
            }
            NotificationType.WEBHOOK -> {
                val chaptersJson = chapters.joinToString(",") {
                    """{"id":"${it.id}","title":"${j(it.title)}","index":${it.index}}"""
                }
                httpClient.post(target.url) {
                    contentType(ContentType.Application.Json)
                    target.token?.let { header("Authorization", "Bearer $it") }
                    setBody(
                        """{"event":"new_chapters","seriesId":"$seriesId","seriesTitle":"${j(seriesTitle)}","newChapters":[$chaptersJson]}"""
                    )
                }
            }
        }
    }

    private fun j(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
