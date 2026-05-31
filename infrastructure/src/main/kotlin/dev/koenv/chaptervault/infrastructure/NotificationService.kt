package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.NewChaptersDiscovered
import dev.koenv.chaptervault.kernel.event.on
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationEvent
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import org.slf4j.LoggerFactory

class NotificationService(
    private val eventBus: EventBus,
    private val notificationApi: NotificationApi,
    private val channelRegistry: NotificationChannelRegistry,
) : NotificationDispatchApi {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    fun start() {
        eventBus.on<NewChaptersDiscovered> { event -> dispatch(event) }
        log.info("NotificationService started")
    }

    private suspend fun dispatch(event: NewChaptersDiscovered) {
        runCatching {
            if (event.chapters.isEmpty()) return
            val targets = notificationApi.listTargets().filter { it.enabled }
            if (targets.isEmpty()) return
            val notifEvent = NotificationEvent(
                seriesId = event.series.id.toString(),
                seriesTitle = event.series.title,
                newChapters = event.chapters.map {
                    NotificationEvent.ChapterSummary(it.id.toString(), it.title, it.chapterIndex)
                },
            )
            targets.forEach { target ->
                val channel = channelRegistry.find(target.type)
                if (channel == null) {
                    log.warn("No channel registered for type '${target.type}' (target '${target.name}'). Use the built-in types or register an extension channel.")
                    return@forEach
                }
                runCatching {
                    channel.send(target.url, target.token, notifEvent)
                }.onFailure { e ->
                    log.warn("Notification to '${target.name}' (${target.type}) failed: ${e.message}")
                }
            }
        }.onFailure { e ->
            log.error("Notification dispatch failed: ${e.message}", e)
        }
    }

    override suspend fun sendTest(targetId: Id): Result<Unit> {
        val target = when (val r = notificationApi.findTarget(targetId)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val channel = channelRegistry.find(target.type)
            ?: return Result.Failure(AppError.InternalError("No channel registered for type '${target.type}'"))
        return runCatching {
            channel.send(
                targetUrl = target.url,
                targetToken = target.token,
                event = NotificationEvent(
                    seriesId = "00000000-0000-0000-0000-000000000000",
                    seriesTitle = "ChapterVault Test",
                    newChapters = listOf(
                        NotificationEvent.ChapterSummary(
                            "00000000-0000-0000-0000-000000000001",
                            "Test Chapter 1",
                            1.0,
                        ),
                    ),
                ),
            )
            Result.Success(Unit)
        }.getOrElse { e ->
            Result.Failure(AppError.InternalError("Test notification failed: ${e.message}"))
        }
    }
}
