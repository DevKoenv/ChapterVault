package dev.koenv.chaptervault.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * **ConfigManager**
 *
 * Centralized manager for loading, storing, updating, and persisting strongly-typed configuration objects.
 * Supports YAML files, default constructor values, and environment variable overrides.
 *
 * ## Features
 * - Reads and writes YAML config files via Jackson.
 * - Automatically merges YAML data with constructor defaults.
 * - Applies environment variable overrides at runtime.
 * - Supports `loadAll()` to load all registered configs.
 * - Provides runtime access and updates to config objects.
 *
 * ## Environment Variable Mapping
 * The naming scheme is:
 * ```
 * <PREFIX>_<CONFIGCLASSNAME>_<FIELD>[_<SUBFIELD>...]
 * ```
 * Example:
 * ```
 * PROJECT_CONFIG_SERVER_HOST
 * ```
 */
object ConfigManager {

    // ------------------------ Configurable Behavior ------------------------

    /** Prefix applied to all environment variable lookups (default "PROJECT") */
    @Volatile
    private var envPrefix: String = "PROJECT"

    /** Enable or disable environment variable overrides (true by default) */
    @Volatile
    private var envOverridesEnabled: Boolean = true

    /**
     * Set the environment variable prefix for all config lookups.
     *
     * @param prefix New prefix (trailing underscores are trimmed)
     */
    fun setEnvPrefix(prefix: String) {
        envPrefix = prefix.trim().trimEnd('_')
    }

    /**
     * Enable or disable environment variable overrides.
     *
     * @param enabled True to enable, false to disable
     */
    fun enableEnvOverrides(enabled: Boolean) {
        envOverridesEnabled = enabled
    }

    // ------------------------ Internal Data ------------------------

    /** Registry storing all registered config entries by class */
    private val registry = ConcurrentHashMap<KClass<*>, ConfigEntry<*>>()

    /** YAML object mapper for reading/writing configuration files */
    private val yamlMapper = ObjectMapper(
        YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
    ).registerKotlinModule()

    /** Internal structure representing a single config entry */
    private data class ConfigEntry<T : Any>(
        val type: KClass<T>,
        val file: File,
        val ref: AtomicReference<T?>
    )

    // ------------------------ Registration ------------------------

    /**
     * Register a configuration class with a corresponding YAML file path.
     *
     * @param clazz The config class type
     * @param filePath Path to the YAML file
     * @throws IllegalStateException if the config class is already registered
     */
    fun <T : Any> registerConfig(clazz: KClass<T>, filePath: String) {
        val file = File(filePath)
        if (registry.containsKey(clazz)) error("Config ${clazz.simpleName} already registered")
        registry[clazz] = ConfigEntry(clazz, file, AtomicReference(null))
    }

    /** Inline reified version of registerConfig for convenience */
    inline fun <reified T : Any> registerConfig(filePath: String) =
        registerConfig(T::class, filePath)

    // ------------------------ Loading ------------------------

    /**
     * Load a single configuration class into memory.
     *
     * If the file does not exist or is empty, defaults are written.
     * Environment variable overrides are applied if enabled.
     *
     * @param clazz Config class type
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> load(clazz: KClass<T>) {
        val entry = registry[clazz] as? ConfigEntry<T> ?: error("Config ${clazz.simpleName} not registered")
        loadEntry(entry)
    }

    /** Inline reified version of load() */
    inline fun <reified T : Any> load() = load(T::class)

    /**
     * Load all registered configuration classes.
     *
     * Each config is:
     * 1. Loaded from YAML or defaults if missing.
     * 2. Merged with constructor defaults.
     * 3. Environment variable overrides applied (runtime only).
     * 4. Persisted back to YAML (merged YAML+defaults, NOT env).
     */
    fun loadAll() {
        registry.values.forEach { entry ->
            loadEntryDynamic(entry)
        }
    }

    // ------------------------ Access ------------------------

    /**
     * Retrieve the loaded instance of a configuration class.
     *
     * @param clazz Config class type
     * @return Loaded configuration instance
     * @throws IllegalStateException if config not loaded yet
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: KClass<T>): T {
        val entry = registry[clazz] as? ConfigEntry<T> ?: error("Config ${clazz.simpleName} not registered")
        return entry.ref.get() ?: error("Config ${clazz.simpleName} not loaded yet")
    }

    /** Inline reified version of get() */
    inline fun <reified T : Any> get() = get(T::class)

    // ------------------------ Update ------------------------

    /**
     * Update an existing loaded config and persist to disk.
     *
     * @param clazz Config class type
     * @param transform Lambda to produce a new config instance
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> update(clazz: KClass<T>, transform: (T) -> T) {
        val entry = registry[clazz] as? ConfigEntry<T> ?: error("Config ${clazz.simpleName} not registered")
        entry.ref.updateAndGet { oldCfg ->
            oldCfg?.let(transform) ?: error("Config ${clazz.simpleName} not loaded yet")
        }
        save(clazz)
    }

    /** Inline reified version of update() */
    inline fun <reified T : Any> update(noinline transform: (T) -> T) = update(T::class, transform)

    // ------------------------ Save ------------------------

    /**
     * Persist a loaded configuration instance to its YAML file.
     *
     * @param clazz Config class type
     * @throws IllegalStateException if config not loaded yet
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> save(clazz: KClass<T>) {
        val entry = registry[clazz] as? ConfigEntry<T> ?: error("Config ${clazz.simpleName} not registered")
        val cfg = entry.ref.get() ?: error("Config ${clazz.simpleName} not loaded yet")
        yamlMapper.writerFor(entry.type.java).writeValue(entry.file, cfg)
    }

    /** Inline reified version of save() */
    inline fun <reified T : Any> save() = save(T::class)

    // ------------------------ Internal Loading Helpers ------------------------

    /** Load a single config entry; writes defaults if file missing */
    private fun <T : Any> loadEntry(entry: ConfigEntry<T>) {
        val file = entry.file

        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            val defaultInstance = createDefaultInstance(entry.type)
            yamlMapper.writerFor(entry.type.java).writeValue(file, defaultInstance)
            // Runtime override with env vars (do not persist)
            val runtime = if (envOverridesEnabled) applyEnvOverrides(defaultInstance, entry.type) else defaultInstance
            entry.ref.set(runtime)
            return
        }

        loadEntryDynamic(entry)
    }

    /**
     * Load & merge logic:
     * - Read YAML file (partial or full)
     * - Merge with constructor defaults
     * - Apply environment variable overrides (runtime only)
     * - Write merged YAML+defaults back to disk
     */
    private fun <T : Any> loadEntryDynamic(entry: ConfigEntry<T>) {
        val file = entry.file
        val clazz = entry.type

        val defaults = createDefaultInstance(clazz)
        val merged: T = try {
            val tmpLoaded: T = yamlMapper.readValue(file, clazz.java)
            mergeWithDefaults(tmpLoaded, defaults)
        } catch (_: Exception) {
            // Empty or malformed file -> fallback to defaults
            defaults
        }

        // Persist YAML+defaults
        yamlMapper.writerFor(clazz.java).writeValue(file, merged)

        // Apply runtime environment variable overrides
        val runtime = if (envOverridesEnabled) applyEnvOverrides(merged, clazz) else merged
        entry.ref.set(runtime)
    }

    // ------------------------ Environment Variable Overrides ------------------------

    /**
     * Apply environment variable overrides recursively to an instance.
     *
     * @param instance Config instance to override
     * @param clazz Type of the config
     * @return New instance with applied env overrides
     */
    private fun <T : Any> applyEnvOverrides(instance: T, clazz: KClass<T>): T {
        val className = clazz.simpleName ?: "CONFIG"
        return applyEnvOverridesRecursive(instance, clazz, listOf(className.uppercase()))
    }

    /** Recursive helper for env overrides, handling nested data classes and primitives */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> applyEnvOverridesRecursive(instance: T, clazz: KClass<T>, pathParts: List<String>): T {
        val ctor = clazz.primaryConstructor ?: return instance
        val args = mutableMapOf<KParameter, Any?>()

        for (param in ctor.parameters) {
            val name = param.name ?: continue
            val prop = clazz.memberProperties.firstOrNull { it.name == name } ?: continue
            val currentValue = prop.getter.call(instance)
            val paramType = param.type

            val envKey = buildEnvKey(pathParts + listOf(name.uppercase()))

            val overrideValue = when {
                isPrimitiveOrString(paramType.classifier as? KClass<*>) ->
                    System.getenv(envKey)?.let { parsePrimitive(it, paramType.classifier as KClass<*>) }

                (paramType.classifier as? KClass<*>)?.isSubclassOf(Enum::class) == true ->
                    System.getenv(envKey)?.let { parseEnum(it, paramType.classifier as KClass<*>) }

                (paramType.classifier as? KClass<*>)?.isData == true -> {
                    if (currentValue == null) {
                        val nestedDefaults = createDefaultInstance(paramType.classifier as KClass<Any>)
                        applyEnvOverridesRecursive(
                            nestedDefaults,
                            paramType.classifier as KClass<Any>,
                            pathParts + listOf(name.uppercase())
                        )
                    } else {
                        applyEnvOverridesRecursive(
                            currentValue,
                            paramType.classifier as KClass<Any>,
                            pathParts + listOf(name.uppercase())
                        )
                    }
                }

                else -> null
            }

            args[param] = overrideValue ?: currentValue
        }

        return ctor.callBy(args)
    }

    /** Build the full environment variable key from path parts */
    private fun buildEnvKey(parts: List<String>): String {
        val keyBody = parts.joinToString("_") { it.replace(".", "_").replace("-", "_") }
        return if (envPrefix.isEmpty()) keyBody else "${envPrefix}_$keyBody"
    }
    /** Check if a class is a primitive type or String */
    private fun isPrimitiveOrString(k: KClass<*>?): Boolean = when (k) {
        String::class, Int::class, Long::class, Short::class, Byte::class, Float::class, Double::class, Boolean::class, Char::class -> true
        else -> false
    }

    /** Parse a string into a primitive type */
    private fun parsePrimitive(raw: String, k: KClass<*>): Any? = when (k) {
        String::class -> raw
        Int::class -> raw.toIntOrNull()
        Long::class -> raw.toLongOrNull()
        Short::class -> raw.toShortOrNull()
        Byte::class -> raw.toByteOrNull()
        Float::class -> raw.toFloatOrNull()
        Double::class -> raw.toDoubleOrNull()
        Boolean::class -> raw.equals("true", ignoreCase = true) || raw == "1"
        Char::class -> raw.firstOrNull()
        else -> null
    }

    /** Parse a string into an enum constant */
    private fun parseEnum(raw: String, k: KClass<*>): Any? {
        @Suppress("UNCHECKED_CAST")
        val enumClass = k as KClass<out Enum<*>>
        return enumClass.java.enumConstants?.firstOrNull { (it as Enum<*>).name.equals(raw, ignoreCase = true) }
    }

    // ------------------------ Merging / Defaults ------------------------

    /**
     * Merge a loaded config with defaults.
     * Any null or missing properties are replaced with defaults.
     */
    private fun <T : Any> mergeWithDefaults(current: T, defaults: T): T {
        val clazz = current::class
        val ctor = clazz.primaryConstructor ?: return current

        val args = ctor.parameters.associateWith { param ->
            val value = clazz.memberProperties.firstOrNull { it.name == param.name }?.getter?.call(current)
            value ?: clazz.memberProperties.firstOrNull { it.name == param.name }?.getter?.call(defaults)
        }

        return ctor.callBy(args)
    }

    /** Create a default instance of a data class using constructor defaults */
    private fun <T : Any> createDefaultInstance(kClass: KClass<T>): T {
        val ctor = kClass.primaryConstructor ?: error("No constructor found for ${kClass.simpleName}")
        return ctor.callBy(emptyMap())
    }
}
