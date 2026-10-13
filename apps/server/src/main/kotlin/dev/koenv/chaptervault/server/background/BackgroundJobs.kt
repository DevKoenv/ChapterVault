package dev.koenv.chaptervault.server.background

import dev.koenv.chaptervault.infrastructure.SeriesRefreshScheduler
import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.AuthApi
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun Application.launchBackgroundJobs(
    config: AppConfig,
    auth: AuthApi,
    projectionService: EventProjectionService,
    executor: TaskExecutorService,
    refreshScheduler: SeriesRefreshScheduler,
) {
    launch { projectionService.start() }
    launch {
        executor.recoverOnBoot()
        executor.start()
    }
    launch { refreshScheduler.start() }
    launch {
        auth.cleanupExpiredSessions()
        val cleanupIntervalMs = config.auth.sessionCleanupIntervalHours * 60 * 60 * 1000L
        while (true) {
            delay(cleanupIntervalMs)
            auth.cleanupExpiredSessions()
        }
    }
}
