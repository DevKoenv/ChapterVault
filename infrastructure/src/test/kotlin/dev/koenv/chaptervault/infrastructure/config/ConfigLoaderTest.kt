package dev.koenv.chaptervault.infrastructure.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class ConfigLoaderTest {
    @Test
    fun `returns defaults when config file does not exist`() {
        val config = ConfigLoader.load("nonexistent-config-path.yaml")
        assertEquals(8080, config.server.port)
        assertEquals("0.0.0.0", config.server.host)
        assertEquals("org.sqlite.JDBC", config.database.driver)
    }

    @Test
    fun `parses server port and host from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("""
            server:
              port: 9090
              host: "localhost"
        """.trimIndent())

        val config = ConfigLoader.load(file.absolutePath)
        assertEquals(9090, config.server.port)
        assertEquals("localhost", config.server.host)
    }

    @Test
    fun `parses database url from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("""
            database:
              url: "jdbc:sqlite:custom.db"
              maxPoolSize: 10
        """.trimIndent())

        val config = ConfigLoader.load(file.absolutePath)
        assertEquals("jdbc:sqlite:custom.db", config.database.url)
        assertEquals(10, config.database.maxPoolSize)
    }

    @Test
    fun `parses storage config from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("""
            storage:
              basePath: "custom/downloads"
              defaultFormat: "FOLDER"
        """.trimIndent())

        val config = ConfigLoader.load(file.absolutePath)
        assertEquals("custom/downloads", config.storage.basePath)
        assertEquals("FOLDER", config.storage.defaultFormat.toString())
    }

    @Test
    fun `uses defaults for missing keys`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("""
            server:
              port: 7070
        """.trimIndent())

        val config = ConfigLoader.load(file.absolutePath)
        assertEquals(7070, config.server.port)
        assertEquals("0.0.0.0", config.server.host)
        assertEquals("org.sqlite.JDBC", config.database.driver)
    }

    @Test
    fun `env var overrides port from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("server:\n  port: 9090")
        val config = ConfigLoader.load(file.absolutePath, env = { if (it == "CHAPTERVAULT_PORT") "7777" else null })
        assertEquals(7777, config.server.port)
    }

    @Test
    fun `env var overrides database url`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_DATABASE_URL") "jdbc:sqlite:env.db" else null })
        assertEquals("jdbc:sqlite:env.db", config.database.url)
    }

    @Test
    fun `env var overrides storage path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_STORAGE_PATH") "/mnt/data" else null })
        assertEquals("/mnt/data", config.storage.basePath)
    }

    @Test
    fun `env var overrides cors origins as comma-separated list`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_CORS_ORIGINS") "http://a.com, http://b.com" else null })
        assertEquals(listOf("http://a.com", "http://b.com"), config.server.corsOrigins)
    }

    @Test
    fun `env var overrides refresh hours`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_REFRESH_HOURS") "12" else null })
        assertEquals(12, config.refresh.intervalHours)
    }

    @Test
    fun `env var overrides mock connector flag`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_MOCK_CONNECTOR") "true" else null })
        assertEquals(true, config.debug.mockConnectorEnabled)
    }

    @Test
    fun `env vars take precedence over yaml values`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("database:\n  url: \"jdbc:sqlite:from-yaml.db\"")
        val config = ConfigLoader.load(file.absolutePath, env = { if (it == "CHAPTERVAULT_DATABASE_URL") "jdbc:sqlite:from-env.db" else null })
        assertEquals("jdbc:sqlite:from-env.db", config.database.url)
    }

    @Test
    fun `invalid env var port falls back to yaml value`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("server:\n  port: 9090")
        val config = ConfigLoader.load(file.absolutePath, env = { if (it == "CHAPTERVAULT_PORT") "not-a-number" else null })
        assertEquals(9090, config.server.port)
    }
}
