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
              libraryPath: "custom/library"
              defaultFormat: "FOLDER"
        """.trimIndent())
        val config = ConfigLoader.load(file.absolutePath)
        assertEquals("custom/library", config.storage.libraryPath)
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
    fun `env var overrides library path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_LIBRARY_PATH") "/mnt/data" else null })
        assertEquals("/mnt/data", config.storage.libraryPath)
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

    @Test
    fun `parses storage libraryPath and thumbnailsPath from yaml`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("""
            storage:
              libraryPath: "custom/library"
              thumbnailsPath: "custom/thumbs"
              defaultFormat: "FOLDER"
        """.trimIndent())
        val config = ConfigLoader.load(file.absolutePath)
        assertEquals("custom/library", config.storage.libraryPath)
        assertEquals("custom/thumbs", config.storage.thumbnailsPath)
    }

    @Test
    fun `CHAPTERVAULT_LIBRARY_PATH overrides library path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_LIBRARY_PATH") "/mnt/lib" else null })
        assertEquals("/mnt/lib", config.storage.libraryPath)
    }

    @Test
    fun `CHAPTERVAULT_THUMBNAILS_PATH overrides thumbnails path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_THUMBNAILS_PATH") "/mnt/thumbs" else null })
        assertEquals("/mnt/thumbs", config.storage.thumbnailsPath)
    }

    @Test
    fun `default database url points to db subdirectory`() {
        val config = ConfigLoader.load("nonexistent.yaml")
        assertEquals("jdbc:sqlite:data/db/chaptervault.db", config.database.url)
    }

    @Test
    fun `CHAPTERVAULT_DATA_DIR sets default db url, library path, and thumbnails path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = { if (it == "CHAPTERVAULT_DATA_DIR") "mydata" else null })
        assertEquals("jdbc:sqlite:mydata/db/chaptervault.db", config.database.url)
        assertEquals("mydata/library", config.storage.libraryPath)
        assertEquals("mydata/thumbnails", config.storage.thumbnailsPath)
    }

    @Test
    fun `explicit CHAPTERVAULT_LIBRARY_PATH overrides CHAPTERVAULT_DATA_DIR derived library path`() {
        val config = ConfigLoader.load("nonexistent.yaml", env = {
            when (it) {
                "CHAPTERVAULT_DATA_DIR" -> "mydata"
                "CHAPTERVAULT_LIBRARY_PATH" -> "/mnt/library"
                else -> null
            }
        })
        assertEquals("jdbc:sqlite:mydata/db/chaptervault.db", config.database.url)
        assertEquals("/mnt/library", config.storage.libraryPath)
        assertEquals("mydata/thumbnails", config.storage.thumbnailsPath)
    }

    @Test
    fun `YAML libraryPath overrides CHAPTERVAULT_DATA_DIR derived library path`(@TempDir dir: Path) {
        val file = dir.resolve("application.yaml").toFile()
        file.writeText("storage:\n  libraryPath: \"yaml/library\"")
        val config = ConfigLoader.load(file.absolutePath, env = { if (it == "CHAPTERVAULT_DATA_DIR") "mydata" else null })
        assertEquals("yaml/library", config.storage.libraryPath)
        assertEquals("mydata/thumbnails", config.storage.thumbnailsPath)
    }
}
