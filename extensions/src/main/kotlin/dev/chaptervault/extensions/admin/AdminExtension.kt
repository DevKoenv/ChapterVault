package dev.chaptervault.extensions.admin

import dev.chaptervault.extensions.shared.ExtensionBase
import dev.chaptervault.kernel.extension.Capability
import dev.chaptervault.kernel.extension.ExtensionContext
import dev.chaptervault.kernel.library.Series
import dev.chaptervault.kernel.runtime.Task
import dev.chaptervault.shared.paging.PageRequest
import dev.chaptervault.shared.paging.Pagination
import dev.chaptervault.shared.result.AppError
import dev.chaptervault.shared.result.Result

class AdminExtension : ExtensionBase() {
    override val id: String = "admin"
    override val name: String = "Admin Panel"
    override val version: String = "1.0.0"

    val capability: Capability = Capability.CanServeAdmin

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
