package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

interface MetadataOverrideApi {
    suspend fun setOverride(
        seriesId: Id,
        field: String,
        value: String,
    ): Result<Unit>

    suspend fun clearOverride(
        seriesId: Id,
        field: String,
    ): Result<Unit>

    suspend fun getOverrides(seriesId: Id): Result<Map<String, String>>
}
