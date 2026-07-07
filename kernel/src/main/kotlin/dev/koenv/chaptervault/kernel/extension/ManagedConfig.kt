package dev.koenv.chaptervault.kernel.extension

interface ManagedConfig {
    fun reload()
    fun save()

    fun getString(path: String, default: String? = null): String?
    fun getInt(path: String, default: Int? = null): Int?
    fun getBoolean(path: String, default: Boolean? = null): Boolean?
    fun getDouble(path: String, default: Double? = null): Double?
    fun get(path: String): Any?
    fun set(path: String, value: Any?)
    fun remove(path: String)
    fun contains(path: String): Boolean
}
