package dev.koenv.chaptervault.kernel.extension

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private fun file(name: String = "config.yml") = tempDir.resolve(name).toFile()

    @Test
    fun `load on missing file returns empty config`() {
        val cfg = YamlConfig(file()).load()
        assertNull(cfg.getString("key"))
        assertEquals("default", cfg.getString("key", "default"))
    }

    @Test
    fun `roundtrip — set and save, reload and get`() {
        val cfg = YamlConfig(file()).load()
        cfg.set("host", "localhost")
        cfg.set("port", 8080)
        cfg.save()

        val cfg2 = YamlConfig(file()).load()
        assertEquals("localhost", cfg2.getString("host"))
        assertEquals(8080, cfg2.getInt("port"))
    }

    @Test
    fun `dot notation — nested path get and set`() {
        val cfg = YamlConfig(file()).load()
        cfg.set("server.host", "localhost")
        cfg.set("server.port", 9090)
        cfg.save()

        val cfg2 = YamlConfig(file()).load()
        assertEquals("localhost", cfg2.getString("server.host"))
        assertEquals(9090, cfg2.getInt("server.port"))
    }

    @Test
    fun `nested map mutation after load does not throw`() {
        val f = file()
        f.writeText("server:\n  host: old\n")
        val cfg = YamlConfig(f).load()
        cfg.set("server.host", "new") // must not throw ClassCastException
        cfg.save()

        val cfg2 = YamlConfig(f).load()
        assertEquals("new", cfg2.getString("server.host"))
    }

    @Test
    fun `set null writes present key with null value`() {
        val cfg = YamlConfig(file()).load()
        cfg.set("key", null)
        cfg.save()

        val cfg2 = YamlConfig(file()).load()
        assertTrue(cfg2.contains("key"))
        assertNull(cfg2.get("key"))
    }

    @Test
    fun `remove deletes key entirely`() {
        val f = file()
        f.writeText("key: value\n")
        val cfg = YamlConfig(f).load()
        cfg.remove("key")
        cfg.save()

        val cfg2 = YamlConfig(f).load()
        assertFalse(cfg2.contains("key"))
    }

    @Test
    fun `contains distinguishes null value from absent key`() {
        val cfg = YamlConfig(file()).load()
        cfg.set("present", null)
        assertTrue(cfg.contains("present"))
        assertFalse(cfg.contains("absent"))
    }

    @Test
    fun `getInt accepts Int and Long from SnakeYAML`() {
        val f = file()
        f.writeText("small: 42\nlarge: 3000000000\n")
        val cfg = YamlConfig(f).load()
        assertEquals(42, cfg.getInt("small"))
        assertEquals(3000000000L.toInt(), cfg.getInt("large")) // truncates — that's expected
    }

    @Test
    fun `getDouble accepts integer YAML value`() {
        val f = file()
        f.writeText("count: 5\n")
        val cfg = YamlConfig(f).load()
        assertEquals(5.0, cfg.getDouble("count"))
    }

    @Test
    fun `getString does not coerce non-string values`() {
        val f = file()
        f.writeText("count: 5\n")
        val cfg = YamlConfig(f).load()
        assertNull(cfg.getString("count"))
        assertEquals("fallback", cfg.getString("count", "fallback"))
    }

    @Test
    fun `load on malformed YAML throws`() {
        val f = file()
        f.writeText("{{not: valid: yaml")
        assertFailsWith<Exception> { YamlConfig(f).load() }
    }

    @Test
    fun `load on non-map root YAML throws`() {
        val f = file()
        f.writeText("just a scalar string\n")
        assertFailsWith<IllegalArgumentException> { YamlConfig(f).load() }
    }

    @Test
    fun `save creates parent directories`() {
        val nested = tempDir.resolve("a/b/config.yml").toFile()
        val cfg = YamlConfig(nested).load()
        cfg.set("key", "value")
        cfg.save()
        assertTrue(nested.exists())
    }

    @Test
    fun `concurrent sets on one config lose no key`() {
        val cfg = YamlConfig(file()).load()
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(16)
        try {
            val jobs = (0 until 16).map { t -> pool.submit { repeat(2000) { n -> cfg.set("key${t}_$n", n) } } }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val missing = (0 until 16).sumOf { t -> (0 until 2000).count { n -> cfg.getInt("key${t}_$n") != n } }
        assertEquals(0, missing, "keys lost by concurrent set()")
    }
}
