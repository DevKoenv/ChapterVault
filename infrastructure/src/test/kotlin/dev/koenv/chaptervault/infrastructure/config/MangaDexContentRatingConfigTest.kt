package dev.koenv.chaptervault.infrastructure.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MangaDexContentRatingConfigTest {
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
    fun `the default is safe and suggestive`() {
        assertEquals(listOf("safe", "suggestive"), AppConfig().connectors.mangadex.contentRatings)
    }

    @Test
    fun `the ratings are read from the config file`(
        @TempDir dir: Path,
    ) {
        val config = load(dir, "connectors:\n  mangadex:\n    contentRatings:\n      - safe\n      - erotica\n")

        assertEquals(listOf("safe", "erotica"), config.connectors.mangadex.contentRatings)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `the environment overrides the config file`(
        @TempDir dir: Path,
    ) {
        val config =
            load(
                dir,
                "connectors:\n  mangadex:\n    contentRatings: [safe]\n",
                env = mapOf("CHAPTERVAULT_MANGADEX_CONTENT_RATINGS" to "safe, suggestive ,pornographic"),
            )

        assertEquals(listOf("safe", "suggestive", "pornographic"), config.connectors.mangadex.contentRatings)
    }

    @Test
    fun `an unknown rating stops the server with a message that names the setting`(
        @TempDir dir: Path,
    ) {
        val error =
            assertFailsWith<IllegalStateException> {
                load(dir, "connectors:\n  mangadex:\n    contentRatings: [safe, adult]\n")
            }

        assertContains(error.message.orEmpty(), "connectors.mangadex.contentRatings")
        assertContains(error.message.orEmpty(), "adult")
    }

    @Test
    fun `an empty list is refused because MangaDex would then apply its own default`(
        @TempDir dir: Path,
    ) {
        val error = assertFailsWith<IllegalStateException> { load(dir, "connectors:\n  mangadex:\n    contentRatings: []\n") }

        assertContains(error.message.orEmpty(), "connectors.mangadex.contentRatings")
    }

    @Test
    fun `ratings are accepted in any letter case`() {
        AppConfig(connectors = ConnectorsConfig(MangaDexConfig(listOf("Safe", "EROTICA")))).validate()
    }
}
