package dev.koenv.chaptervault.extensions.admin

import dev.koenv.chaptervault.extensions.shared.ExtensionBase
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result

class AdminExtension : ExtensionBase() {
    override val id: String = "admin"
    override val name: String = "Admin Panel"
    override val version: String = "1.0.0"

    override val capabilities: Set<Capability> = setOf(Capability.CanServeAdmin)

    private var context: ExtensionContext? = null

    override suspend fun onLoad(ctx: ExtensionContext) {
        context = ctx
    }

    suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> =
        context?.libraryRead?.listSeries(request)
            ?: Result.Failure(AppError.InternalError("AdminExtension not initialized"))

    suspend fun listTasks(request: PageRequest): Result<Pagination<Task>> =
        context?.system?.listTasks(request)
            ?: Result.Failure(AppError.InternalError("AdminExtension not initialized"))
}
