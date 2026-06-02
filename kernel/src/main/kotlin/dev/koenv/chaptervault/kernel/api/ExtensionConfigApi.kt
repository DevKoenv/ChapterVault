package dev.koenv.chaptervault.kernel.api

interface ExtensionConfigApi {
    suspend fun getAll(extensionId: String): Map<String, String>

    suspend fun setAll(
        extensionId: String,
        values: Map<String, String>,
    )
}
