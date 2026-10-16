package dev.koenv.chaptervault.infrastructure.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigLoaderWarningsTest {
    private val warnings = mutableListOf<String>()

    private fun load(
        dir: Path,
        yaml: String,
        env: Map<String, String> = emptyMap(),
    ): AppConfig {
        val file = dir.resolve("config.yaml").toFile().also { it.writeText(yaml.trimIndent()) }
        return ConfigLoader.load(file.absolutePath, env = { env[it] }, onWarning = { warnings += it })
    }

    @Test
    fun `a value of the wrong type is reported and the default is used`(
        @TempDir dir: Path,
    ) {
        val config = load(dir, "server:\n  port: \"8080\"\n  host: 12\n")

        assertEquals(8080, config.server.port)
        assertEquals("0.0.0.0", config.server.host)
        assertTrue(warnings.any { "server.port" in it && "number" in it }, warnings.toString())
        assertTrue(warnings.any { "server.host" in it && "text" in it }, warnings.toString())
    }

    @Test
    fun `a misspelled key is reported with its full path`(
        @TempDir dir: Path,
    ) {
        load(dir, "server:\n  prot: 9090\nauth:\n  rateLimiting:\n    login:\n      maxAttemps: 3\nstorgae:\n  x: 1\n")

        assertTrue(warnings.any { "server.prot" in it }, warnings.toString())
        assertTrue(warnings.any { "auth.rateLimiting.login.maxAttemps" in it }, warnings.toString())
        assertTrue(warnings.any { "storgae" in it }, warnings.toString())
    }

    @Test
    fun `log level is no longer a setting and is reported as unknown`(
        @TempDir dir: Path,
    ) {
        load(dir, "log:\n  level: DEBUG\n")

        assertTrue(warnings.any { "log" in it && "Unknown setting" in it }, warnings.toString())
    }

    @Test
    fun `database settings for a remote server such as PostgreSQL are accepted without warnings`(
        @TempDir dir: Path,
    ) {
        val config =
            load(
                dir,
                """
                database:
                  driver: "org.postgresql.Driver"
                  url: "jdbc:postgresql://db.example.com:5432/chaptervault"
                  username: "chaptervault"
                  password: "secret"
                  maxPoolSize: 20
                """.trimIndent(),
            )

        assertEquals("org.postgresql.Driver", config.database.driver)
        assertEquals("jdbc:postgresql://db.example.com:5432/chaptervault", config.database.url)
        assertEquals("chaptervault", config.database.username)
        assertEquals("secret", config.database.password)
        assertEquals(20, config.database.maxPoolSize)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a section that is not a section is reported`(
        @TempDir dir: Path,
    ) {
        load(dir, "server: 8080\n")

        assertTrue(warnings.any { "server" in it && "section" in it }, warnings.toString())
    }

    @Test
    fun `a correct file produces no warnings`(
        @TempDir dir: Path,
    ) {
        load(dir, ConfigLoader.defaultConfigContent())

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a file that is not valid YAML stops the server with a message that names the file`(
        @TempDir dir: Path,
    ) {
        val error = assertFailsWith<IllegalStateException> { load(dir, "server: [unclosed\n") }

        assertTrue("config.yaml" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `an empty file is fine`(
        @TempDir dir: Path,
    ) {
        assertEquals(8080, load(dir, "# nothing here\n").server.port)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `sessionCleanupIntervalHours is read from the file`(
        @TempDir dir: Path,
    ) {
        val config = load(dir, "auth:\n  sessionCleanupIntervalHours: 6\n")

        assertEquals(6, config.auth.sessionCleanupIntervalHours)
    }

    @Test
    fun `notification and update settings are read from the file`(
        @TempDir dir: Path,
    ) {
        val config = load(dir, "notifications:\n  allowLocalTargets: true\nupdates:\n  checkEnabled: false\n")

        assertTrue(config.notifications.allowLocalTargets)
        assertFalse(config.updates.checkEnabled)
    }

    @Test
    fun `environment variables override the new settings`(
        @TempDir dir: Path,
    ) {
        val config =
            load(
                dir,
                "updates:\n  checkEnabled: true\n",
                env = mapOf("CHAPTERVAULT_UPDATE_CHECK" to "false", "CHAPTERVAULT_NOTIFICATIONS_ALLOW_LOCAL" to "true"),
            )

        assertFalse(config.updates.checkEnabled)
        assertTrue(config.notifications.allowLocalTargets)
    }

    @Test
    fun `an invalid value in an environment variable is reported`(
        @TempDir dir: Path,
    ) {
        val config = load(dir, "server:\n  port: 9090\n", env = mapOf("CHAPTERVAULT_PORT" to "eighty"))

        assertEquals(9090, config.server.port)
        assertTrue(warnings.any { "CHAPTERVAULT_PORT" in it }, warnings.toString())
    }
}
