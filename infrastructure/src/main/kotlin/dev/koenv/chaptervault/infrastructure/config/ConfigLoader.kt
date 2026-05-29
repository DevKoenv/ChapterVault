package dev.koenv.chaptervault.infrastructure.config

import dev.koenv.chaptervault.shared.format.ChapterFormat
import org.yaml.snakeyaml.Yaml
import java.io.File

object ConfigLoader {
    fun load(
        configPath: String? = null,
        env: (String) -> String? = System::getenv,
    ): AppConfig {
        val dataDir = env("CHAPTERVAULT_DATA_DIR") ?: "data"

        // Derived defaults from dataDir — YAML and explicit env vars override these per-field
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

        val base =
            if (!file.exists()) {
                derived
            } else {
                @Suppress("UNCHECKED_CAST")
                val map = Yaml().load<Map<String, Any>>(file.inputStream()) ?: return applyEnv(derived, env)

                val server =
                    (map["server"] as? Map<*, *>)?.let { s ->
                        @Suppress("UNCHECKED_CAST")
                        val corsOrigins = (s["corsOrigins"] as? List<String>) ?: emptyList()
                        ServerConfig(
                            port = (s["port"] as? Int) ?: derived.server.port,
                            host = (s["host"] as? String) ?: derived.server.host,
                            corsOrigins = corsOrigins,
                        )
                    } ?: derived.server

                val database =
                    (map["database"] as? Map<*, *>)?.let { d ->
                        DatabaseConfig(
                            driver = (d["driver"] as? String) ?: derived.database.driver,
                            url = (d["url"] as? String) ?: derived.database.url,
                            maxPoolSize = (d["maxPoolSize"] as? Int) ?: derived.database.maxPoolSize,
                        )
                    } ?: derived.database

                val storage =
                    (map["storage"] as? Map<*, *>)?.let { s ->
                        StorageConfig(
                            libraryPath = (s["libraryPath"] as? String) ?: derived.storage.libraryPath,
                            thumbnailsPath = (s["thumbnailsPath"] as? String) ?: derived.storage.thumbnailsPath,
                            defaultFormat =
                                (s["defaultFormat"] as? String)
                                    ?.let { runCatching { ChapterFormat.fromString(it) }.getOrNull() }
                                    ?: derived.storage.defaultFormat,
                        )
                    } ?: derived.storage

                val log =
                    (map["log"] as? Map<*, *>)?.let { l ->
                        LogConfig(level = (l["level"] as? String) ?: derived.log.level)
                    } ?: derived.log

                val refresh =
                    (map["refresh"] as? Map<*, *>)?.let { r ->
                        RefreshConfig(intervalHours = (r["intervalHours"] as? Int) ?: derived.refresh.intervalHours)
                    } ?: derived.refresh

                val debug =
                    (map["debug"] as? Map<*, *>)?.let { d ->
                        DebugConfig(mockConnectorEnabled = (d["mockConnectorEnabled"] as? Boolean) ?: derived.debug.mockConnectorEnabled)
                    } ?: derived.debug

                val auth =
                    (map["auth"] as? Map<*, *>)?.let { a ->
                        val rl =
                            (a["rateLimiting"] as? Map<*, *>)?.let { r ->
                                @Suppress("UNCHECKED_CAST")
                                val trustedNetworks =
                                    (r["trustedNetworks"] as? List<String>)
                                        ?: derived.auth.rateLimiting.trustedNetworks

                                @Suppress("UNCHECKED_CAST")
                                val trustedProxies =
                                    (r["trustedProxies"] as? List<String>)
                                        ?: derived.auth.rateLimiting.trustedProxies
                                val login =
                                    (r["login"] as? Map<*, *>)?.let { l ->
                                        EndpointLimitConfig(
                                            maxAttempts = (l["maxAttempts"] as? Int) ?: derived.auth.rateLimiting.login.maxAttempts,
                                            windowMinutes = (l["windowMinutes"] as? Int) ?: derived.auth.rateLimiting.login.windowMinutes,
                                        )
                                    } ?: derived.auth.rateLimiting.login
                                val register =
                                    (r["register"] as? Map<*, *>)?.let { reg ->
                                        EndpointLimitConfig(
                                            maxAttempts = (reg["maxAttempts"] as? Int) ?: derived.auth.rateLimiting.register.maxAttempts,
                                            windowMinutes =
                                                (reg["windowMinutes"] as? Int) ?: derived.auth.rateLimiting.register.windowMinutes,
                                        )
                                    } ?: derived.auth.rateLimiting.register
                                RateLimitConfig(
                                    enabled = (r["enabled"] as? Boolean) ?: derived.auth.rateLimiting.enabled,
                                    trustedNetworks = trustedNetworks,
                                    trustedProxies = trustedProxies,
                                    login = login,
                                    register = register,
                                )
                            } ?: derived.auth.rateLimiting
                        AuthConfig(rateLimiting = rl)
                    } ?: derived.auth

                AppConfig(server = server, database = database, storage = storage, log = log, refresh = refresh, debug = debug, auth = auth)
            }

        return applyEnv(base, env)
    }

    fun defaultConfigContent(): String =
        """
        # ChapterVault configuration
        # Edit this file and restart the server to apply changes.
        # All values shown are the defaults.
        # Environment variables (CHAPTERVAULT_*) override these settings.

        server:
          port: 8080
          # Bind address — 0.0.0.0 accepts connections on all interfaces.
          host: "0.0.0.0"
          # Allowed CORS origins. Empty = allow all origins (fine for local use).
          # corsOrigins:
          #   - "http://192.168.1.10:3000"

        storage:
          # How downloaded chapters are stored on disk.
          # CBZ    — single zip archive per chapter (default, space-efficient)
          # FOLDER — one image file per page inside a directory
          defaultFormat: CBZ

        refresh:
          # Hours between automatic library refreshes. 0 to disable.
          intervalHours: 24

        auth:
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

    // Env vars take precedence over YAML. All vars are prefixed CHAPTERVAULT_.
    // CHAPTERVAULT_DATA_DIR sets the default root for db/, library/, thumbnails/ (Task 3).
    // CHAPTERVAULT_PORT, CHAPTERVAULT_HOST, CHAPTERVAULT_CORS_ORIGINS (comma-separated),
    // CHAPTERVAULT_DATABASE_URL, CHAPTERVAULT_LIBRARY_PATH, CHAPTERVAULT_THUMBNAILS_PATH,
    // CHAPTERVAULT_REFRESH_HOURS, CHAPTERVAULT_MOCK_CONNECTOR (true/false)
    private fun applyEnv(
        base: AppConfig,
        env: (String) -> String?,
    ): AppConfig =
        base.copy(
            server =
                base.server.copy(
                    port = env("CHAPTERVAULT_PORT")?.toIntOrNull() ?: base.server.port,
                    host = env("CHAPTERVAULT_HOST") ?: base.server.host,
                    corsOrigins =
                        env("CHAPTERVAULT_CORS_ORIGINS")
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: base.server.corsOrigins,
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
                    intervalHours = env("CHAPTERVAULT_REFRESH_HOURS")?.toIntOrNull() ?: base.refresh.intervalHours,
                ),
            debug =
                base.debug.copy(
                    mockConnectorEnabled =
                        env("CHAPTERVAULT_MOCK_CONNECTOR")?.toBooleanStrictOrNull()
                            ?: base.debug.mockConnectorEnabled,
                ),
        )
}
