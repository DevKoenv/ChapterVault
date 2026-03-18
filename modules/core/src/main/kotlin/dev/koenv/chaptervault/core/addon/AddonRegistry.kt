package dev.koenv.chaptervault.core.addon

interface AddonRegistry {
    fun getAllAddons(): List<AddonInfo>
    fun getAddon(id: String): AddonInfo?
    fun enableAddon(id: String)
    fun disableAddon(id: String)
    fun reloadAddon(id: String)
    fun removeAddon(id: String)
    fun getErrors(id: String): List<AddonError>
}
