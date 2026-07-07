package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.kernel.extension.ManagedConfig
import dev.koenv.chaptervault.kernel.extension.YamlConfig
import java.io.File

class DefaultManagedConfig(file: File) : ManagedConfig {
    private val yaml = YamlConfig(file).load()

    override fun reload() { yaml.load() }
    override fun save() = yaml.save()
    override fun getString(path: String, default: String?) = yaml.getString(path, default)
    override fun getInt(path: String, default: Int?) = yaml.getInt(path, default)
    override fun getBoolean(path: String, default: Boolean?) = yaml.getBoolean(path, default)
    override fun getDouble(path: String, default: Double?) = yaml.getDouble(path, default)
    override fun get(path: String) = yaml.get(path)
    override fun set(path: String, value: Any?) = yaml.set(path, value)
    override fun remove(path: String) = yaml.remove(path)
    override fun contains(path: String) = yaml.contains(path)
}
