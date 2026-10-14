package dev.koenv.chaptervault.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigValidatorLibraryPathTest {
    @TempDir
    lateinit var library: Path

    @Test
    fun `an empty library has no foreign content`() {
        assertEquals(emptyList(), ConfigValidator.foreignLibraryEntries(library.toString()))
    }

    @Test
    fun `series directories and the trash directory are ours`() {
        Files.createDirectories(library.resolve(UUID.randomUUID().toString()))
        Files.createDirectories(library.resolve(".trash"))

        assertEquals(emptyList(), ConfigValidator.foreignLibraryEntries(library.toString()))
    }

    @Test
    fun `other files and folders are reported by name`() {
        Files.createDirectories(library.resolve(UUID.randomUUID().toString()))
        Files.createDirectories(library.resolve("Photos"))
        Files.writeString(library.resolve("notes.txt"), "mine")

        assertEquals(listOf("Photos", "notes.txt"), ConfigValidator.foreignLibraryEntries(library.toString()))
    }

    @Test
    fun `a library path that does not exist yet has no foreign content`() {
        assertEquals(emptyList(), ConfigValidator.foreignLibraryEntries(library.resolve("missing").toString()))
    }
}
