package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * One level of the YAML file. Reads typed values and says so when a value has the wrong type or a key is not one the
 * server knows, instead of quietly using the default: a misspelled `prot: 9090` should not look like it worked.
 */
private class Section(
    private val path: String,
    private val map: Map<*, *>,
    private val warn: (String) -> Unit,
) {
    private val used = mutableSetOf<String>()

    private fun full(key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun <T : Any> typed(
        key: String,
        expected: String,
        default: T,
        cast: (Any) -> T?,
    ): T {
        used += key
        val value = map[key] ?: return default
        return cast(value) ?: default.also { warn("${full(key)} must be $expected but is '$value'; using $it") }
    }

    fun int(
        key: String,
        default: Int,
    ): Int = typed(key, "a number", default) { it as? Int }

    fun string(
        key: String,
        default: String,
    ): String = typed(key, "text", default) { it as? String }

    fun bool(
        key: String,
        default: Boolean,
    ): Boolean = typed(key, "true or false", default) { it as? Boolean }

    fun strings(
        key: String,
        default: List<String>,
    ): List<String> =
        typed(key, "a list of text values", default) { value ->
            (value as? List<*>)
                ?.takeIf { l ->
                    l.all { it is String }
                }?.filterIsInstance<String>()
        }

    fun child(key: String): Section? {
        used += key
        val value = map[key] ?: return null
        if (value !is Map<*, *>) {
            warn("${full(key)} must be a section with settings below it but is '$value'; using defaults")
            return null
        }
        return Section(full(key), value, warn)
    }

    /** Call when everything this section understands has been read; anything left over is reported. */
    fun finish() {
        map.keys.map { it.toString() }.filterNot { it in used }.forEach {
            warn(
                "Unknown setting '${full(it)}' in the config file; it is ignored",
            )
        }
    }
}

object ConfigLoader {
    fun load(
        configPath: String? = null,
        env: (String) -> String? = System::getenv,
        onWarning: (String) -> Unit = { logger.warn { it } },
    ): AppConfig {
        val dataDir = env("CHAPTERVAULT_DATA_DIR") ?: "data"

        // base config from dataDir, overridable per-field via YAML or env vars
        val derived =
            AppConfig(
                database = DatabaseConfig(url = "jdbc:sqlite:$dataDir/db/chaptervault.db"),
                storage =
                    StorageConfig(
                        libraryPath = "$dataDir/library",
                        thumbnailsPath = "$dataDir/thumbnails",
                    ),
            )

        val resolvedPath = configPath ?: "$dataDir/config.yaml"
        val file = File(resolvedPath)

        if (configPath == null && !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(defaultConfigContent())
        }

        val base = if (!file.exists()) derived else fromFile(file, derived, onWarning)
        return applyEnv(base, env, onWarning).also { it.validate() }
    }

    private fun fromFile(
        file: File,
        derived: AppConfig,
        warn: (String) -> Unit,
    ): AppConfig {
        val root =
            try {
                file.inputStream().use { Yaml().load<Any?>(it) }
            } catch (e: YAMLException) {
                throw IllegalStateException("${file.path} is not valid YAML: ${e.message?.lineSequence()?.firstOrNull()}", e)
            } ?: return derived
        if (root !is Map<*, *>) {
            warn("${file.path} must contain settings in sections (server:, storage:, ...); its content is ignored")
            return derived
        }
        val top = Section("", root, warn)
        val config =
            AppConfig(
                server = top.child("server")?.let { serverFrom(it, derived.server) } ?: derived.server,
                database = top.child("database")?.let { databaseFrom(it, derived.database) } ?: derived.database,
                storage = top.child("storage")?.let { storageFrom(it, derived.storage, warn) } ?: derived.storage,
                refresh = top.child("refresh")?.let { refreshFrom(it, derived.refresh) } ?: derived.refresh,
                tasks = top.child("tasks")?.let { tasksFrom(it, derived.tasks) } ?: derived.tasks,
                debug = top.child("debug")?.let { debugFrom(it, derived.debug) } ?: derived.debug,
                auth = top.child("auth")?.let { authFrom(it, derived.auth) } ?: derived.auth,
                notifications = top.child("notifications")?.let { notificationsFrom(it, derived.notifications) } ?: derived.notifications,
                updates = top.child("updates")?.let { updatesFrom(it, derived.updates) } ?: derived.updates,
                connectors = top.child("connectors")?.let { connectorsFrom(it, derived.connectors) } ?: derived.connectors,
            )
        top.finish()
        return config
    }

    private fun serverFrom(
        s: Section,
        d: ServerConfig,
    ) = ServerConfig(
        port = s.int("port", d.port),
        host = s.string("host", d.host),
        corsOrigins = s.strings("corsOrigins", d.corsOrigins),
        publicUrl = s.string("publicUrl", "").ifBlank { null } ?: d.publicUrl,
    ).also { s.finish() }

    private fun databaseFrom(
        s: Section,
        d: DatabaseConfig,
    ) = DatabaseConfig(
        driver = s.string("driver", d.driver),
        url = s.string("url", d.url),
    ).also { s.finish() }

    private fun storageFrom(
        s: Section,
        d: StorageConfig,
        warn: (String) -> Unit,
    ): StorageConfig {
        val format = s.string("defaultFormat", "")
        val defaultFormat =
            if (format.isEmpty()) {
                d.defaultFormat
            } else {
                runCatching { ChapterFormat.fromString(format) }.getOrElse {
                    warn("storage.defaultFormat must be CBZ or FOLDER but is '$format'; using ${d.defaultFormat}")
                    d.defaultFormat
                }
            }
        return StorageConfig(
            libraryPath = s.string("libraryPath", d.libraryPath),
            thumbnailsPath = s.string("thumbnailsPath", d.thumbnailsPath),
            defaultFormat = defaultFormat,
        ).also { s.finish() }
    }

    private fun refreshFrom(
        s: Section,
        d: RefreshConfig,
    ) = RefreshConfig(intervalHours = s.int("intervalHours", d.intervalHours)).also { s.finish() }

    private fun tasksFrom(
        s: Section,
        d: TasksConfig,
    ) = TasksConfig(retentionDays = s.int("retentionDays", d.retentionDays)).also { s.finish() }

    private fun debugFrom(
        s: Section,
        d: DebugConfig,
    ) = DebugConfig(mockConnectorEnabled = s.bool("mockConnectorEnabled", d.mockConnectorEnabled)).also { s.finish() }

    private fun notificationsFrom(
        s: Section,
        d: NotificationConfig,
    ) = NotificationConfig(allowLocalTargets = s.bool("allowLocalTargets", d.allowLocalTargets)).also { s.finish() }

    private fun updatesFrom(
        s: Section,
        d: UpdateConfig,
    ) = UpdateConfig(checkEnabled = s.bool("checkEnabled", d.checkEnabled)).also { s.finish() }

    private fun connectorsFrom(
        s: Section,
        d: ConnectorsConfig,
    ) = ConnectorsConfig(
        mangadex =
            s.child("mangadex")?.let { m ->
                MangaDexConfig(contentRatings = m.strings("contentRatings", d.mangadex.contentRatings)).also { m.finish() }
            } ?: d.mangadex,
    ).also { s.finish() }

    private fun authFrom(
        a: Section,
        defaults: AuthConfig,
    ): AuthConfig {
        val rateLimiting =
            a.child("rateLimiting")?.let { r ->
                val d = defaults.rateLimiting

                fun endpoint(
                    name: String,
                    fallback: EndpointLimitConfig,
                ): EndpointLimitConfig =
                    r.child(name)?.let { e ->
                        EndpointLimitConfig(
                            maxAttempts = e.int("maxAttempts", fallback.maxAttempts),
                            windowMinutes = e.int("windowMinutes", fallback.windowMinutes),
                        ).also { e.finish() }
                    } ?: fallback
                RateLimitConfig(
                    enabled = r.bool("enabled", d.enabled),
                    trustedNetworks = r.strings("trustedNetworks", d.trustedNetworks),
                    trustedProxies = r.strings("trustedProxies", d.trustedProxies),
                    login = endpoint("login", d.login),
                    register = endpoint("register", d.register),
                ).also { r.finish() }
            } ?: defaults.rateLimiting
        return AuthConfig(
            registrationEnabled = a.bool("registrationEnabled", defaults.registrationEnabled),
            rateLimiting = rateLimiting,
            sessionCleanupIntervalHours = a.int("sessionCleanupIntervalHours", defaults.sessionCleanupIntervalHours),
        ).also { a.finish() }
    }

    fun defaultConfigContent(): String =
        """
        # ChapterVault configuration
        # Edit this file and restart the server to apply changes.
        # All values shown are the defaults.
        # Environment variables (CHAPTERVAULT_*) override these settings.
        # Misspelled or mistyped settings are reported in the log at startup.

        server:
          port: 8080
          # Bind address, 0.0.0.0 accepts connections on all interfaces
          host: "0.0.0.0"
          # Browser origins that may call the API from another site, e.g. a separately hosted web UI.
          # Empty = no cross-origin access (the API is still reachable from apps, OPDS readers and the same origin).
          # corsOrigins:
          #   - "http://192.168.1.10:3000"
          # Scheme and host OPDS readers use to reach this server; the OPDS search needs an absolute address.
          # Unset = taken from each request. Set it behind a reverse proxy that serves HTTPS.
          # publicUrl: "https://manga.example.com"

        storage:
          # How downloaded chapters are stored on disk.
          # CBZ    - single zip archive per chapter (default, space-efficient)
          # FOLDER - one image file per page inside a directory
          defaultFormat: CBZ

        refresh:
          # Hours between automatic library refreshes. 0 to disable.
          intervalHours: 24

        tasks:
          # Days to keep finished (completed, failed, cancelled) task records. 0 keeps them forever.
          retentionDays: 30

        updates:
          # Ask GitHub once a day whether a newer ChapterVault release exists (shown in the log and /system/updates).
          # false = the server never contacts GitHub.
          checkEnabled: true

        connectors:
          mangadex:
            # Which content ratings a MangaDex search returns: safe, suggestive, erotica, pornographic.
            # You are responsible for what you enable and download (see "Content sources" in the README).
            contentRatings:
              - safe
              - suggestive

        notifications:
          # Notification targets may not point at this machine (localhost, loopback, link-local such as the cloud
          # metadata address). Addresses on your LAN are always allowed. Set true to allow localhost targets too.
          allowLocalTargets: false

        auth:
          # Allow anyone to create a USER account via POST /auth/register.
          # When false (default), only the bootstrap admin account is created automatically.
          # Enable this only if you want to share the server with other users.
          registrationEnabled: false
          # Hours between sweeps that delete expired login sessions.
          sessionCleanupIntervalHours: 24
          rateLimiting:
            enabled: true
            # Addresses and subnets that bypass rate limiting entirely.
            # Defaults cover localhost and all RFC-1918 private networks.
            trustedNetworks:
              - "127.0.0.0/8"
              - "::1/128"
              - "10.0.0.0/8"
              - "172.16.0.0/12"
              - "192.168.0.0/16"
            # If running behind a reverse proxy, list its IP(s) here so the real
            # client address is read from X-Forwarded-For rather than the socket.
            # trustedProxies:
            #   - "172.18.0.1"
            login:
              maxAttempts: 10
              windowMinutes: 15
            register:
              maxAttempts: 5
              windowMinutes: 60
        """.trimIndent() + "\n"

    private fun applyEnv(
        base: AppConfig,
        env: (String) -> String?,
        warn: (String) -> Unit,
    ): AppConfig {
        fun int(name: String): Int? =
            env(name)?.let { value -> value.toIntOrNull().also { if (it == null) warn("$name must be a number but is '$value'; ignored") } }

        fun bool(name: String): Boolean? =
            env(name)?.let { value ->
                value.toBooleanStrictOrNull().also { if (it == null) warn("$name must be true or false but is '$value'; ignored") }
            }

        return base.copy(
            server =
                base.server.copy(
                    port = int("CHAPTERVAULT_PORT") ?: base.server.port,
                    host = env("CHAPTERVAULT_HOST") ?: base.server.host,
                    corsOrigins =
                        env("CHAPTERVAULT_CORS_ORIGINS")
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: base.server.corsOrigins,
                    publicUrl = env("CHAPTERVAULT_PUBLIC_URL")?.ifBlank { null } ?: base.server.publicUrl,
                ),
            database =
                base.database.copy(
                    url = env("CHAPTERVAULT_DATABASE_URL") ?: base.database.url,
                ),
            storage =
                base.storage.copy(
                    libraryPath = env("CHAPTERVAULT_LIBRARY_PATH") ?: base.storage.libraryPath,
                    thumbnailsPath = env("CHAPTERVAULT_THUMBNAILS_PATH") ?: base.storage.thumbnailsPath,
                ),
            refresh =
                base.refresh.copy(
                    intervalHours = int("CHAPTERVAULT_REFRESH_HOURS") ?: base.refresh.intervalHours,
                ),
            tasks =
                base.tasks.copy(
                    retentionDays = int("CHAPTERVAULT_TASK_RETENTION_DAYS") ?: base.tasks.retentionDays,
                ),
            debug =
                base.debug.copy(
                    mockConnectorEnabled = bool("CHAPTERVAULT_MOCK_CONNECTOR") ?: base.debug.mockConnectorEnabled,
                ),
            auth =
                base.auth.copy(
                    registrationEnabled = bool("CHAPTERVAULT_AUTH_REGISTRATION_ENABLED") ?: base.auth.registrationEnabled,
                ),
            notifications =
                base.notifications.copy(
                    allowLocalTargets = bool("CHAPTERVAULT_NOTIFICATIONS_ALLOW_LOCAL") ?: base.notifications.allowLocalTargets,
                ),
            updates =
                base.updates.copy(
                    checkEnabled = bool("CHAPTERVAULT_UPDATE_CHECK") ?: base.updates.checkEnabled,
                ),
            connectors =
                base.connectors.copy(
                    mangadex =
                        base.connectors.mangadex.copy(
                            contentRatings =
                                env("CHAPTERVAULT_MANGADEX_CONTENT_RATINGS")
                                    ?.split(",")
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotEmpty() }
                                    ?: base.connectors.mangadex.contentRatings,
                        ),
                ),
        )
    }
}
