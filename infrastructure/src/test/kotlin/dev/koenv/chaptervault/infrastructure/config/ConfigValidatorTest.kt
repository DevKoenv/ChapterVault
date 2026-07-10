package dev.koenv.chaptervault.infrastructure.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ConfigValidatorTest {
    private val valid = AppConfig()

    @Test
    fun `valid default config passes`() {
        valid.validate()
    }

    @Test
    fun `invalid port fails with descriptive message`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid.copy(server = valid.server.copy(port = 0)).validate()
            }
        assertContains(ex.message!!, "server.port")
    }

    @Test
    fun `port 65536 fails`() {
        assertThrows<IllegalStateException> {
            valid.copy(server = valid.server.copy(port = 65536)).validate()
        }
    }

    @Test
    fun `blank host fails`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid.copy(server = valid.server.copy(host = "")).validate()
            }
        assertContains(ex.message!!, "server.host")
    }

    @Test
    fun `blank database url fails`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid.copy(database = valid.database.copy(url = "")).validate()
            }
        assertContains(ex.message!!, "database.url")
    }

    @Test
    fun `blank library path fails`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid.copy(storage = valid.storage.copy(libraryPath = "  ")).validate()
            }
        assertContains(ex.message!!, "storage.libraryPath")
    }

    @Test
    fun `invalid log level fails`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid.copy(log = LogConfig(level = "VERBOSE")).validate()
            }
        assertContains(ex.message!!, "log.level")
    }

    @Test
    fun `multiple errors reported together`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid
                    .copy(
                        server = valid.server.copy(port = -1, host = ""),
                        database = valid.database.copy(url = ""),
                    ).validate()
            }
        assertContains(ex.message!!, "server.port")
        assertContains(ex.message!!, "server.host")
        assertContains(ex.message!!, "database.url")
    }

    @Test
    fun `zero login maxAttempts fails`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                valid
                    .copy(
                        auth =
                            valid.auth.copy(
                                rateLimiting =
                                    valid.auth.rateLimiting.copy(
                                        login =
                                            valid.auth.rateLimiting.login
                                                .copy(maxAttempts = 0),
                                    ),
                            ),
                    ).validate()
            }
        assertContains(ex.message!!, "login.maxAttempts")
    }
}
