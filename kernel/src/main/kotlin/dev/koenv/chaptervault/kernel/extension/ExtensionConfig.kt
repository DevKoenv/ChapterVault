package dev.koenv.chaptervault.kernel.extension

interface ExtensionConfig {
    fun get(key: String): String?

    fun getOrDefault(
        key: String,
        default: String,
    ): String = get(key) ?: default
}

data class ExtensionConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType,
    val required: Boolean = false,
    val default: String? = null,
    val description: String? = null,
)

enum class ConfigFieldType { STRING, INTEGER, BOOLEAN, PASSWORD }
