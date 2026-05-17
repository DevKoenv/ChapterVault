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
}
