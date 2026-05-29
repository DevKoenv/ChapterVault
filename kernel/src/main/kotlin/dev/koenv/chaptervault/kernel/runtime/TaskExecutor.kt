package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.result.Result

interface TaskExecutor {
    suspend fun execute(task: Task): Result<Unit>

    fun supports(taskType: TaskType): Boolean
}
