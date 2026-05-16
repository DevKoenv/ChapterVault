package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.utils.Id
import kotlin.time.Duration

interface TaskScheduler {
    suspend fun schedule(task: Task, delay: Duration): Id
    suspend fun scheduleRecurring(task: Task, interval: Duration): Id
    suspend fun cancel(scheduleId: Id)
}
