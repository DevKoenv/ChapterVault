package dev.koenv.chaptervault.server

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultManagedConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private fun file(name: String = "config.yml") = tempDir.resolve(name).toFile()

    @Test
    fun `getString returns value from file`() {
        val f = file()
        f.writeText("baseUrl: https://example.com\n")
        val cfg = DefaultManagedConfig(f)
        assertEquals("https://example.com", cfg.getString("baseUrl"))
    }

    @Test
    fun `reload discards unsaved in-memory changes`() {
        val f = file()
        f.writeText("key: original\n")
        val cfg = DefaultManagedConfig(f)
        cfg.set("key", "modified")
        assertEquals("modified", cfg.getString("key"))
        cfg.reload()
        assertEquals("original", cfg.getString("key"))
    }

    @Test
    fun `save writes changes to disk`() {
        val f = file()
        f.writeText("key: original\n")
        val cfg = DefaultManagedConfig(f)
        cfg.set("key", "updated")
        cfg.save()
        val cfg2 = DefaultManagedConfig(f)
        assertEquals("updated", cfg2.getString("key"))
    }

    @Test
    fun `missing file starts empty`() {
        val cfg = DefaultManagedConfig(file("nonexistent.yml"))
        assertNull(cfg.getString("key"))
    }
}
