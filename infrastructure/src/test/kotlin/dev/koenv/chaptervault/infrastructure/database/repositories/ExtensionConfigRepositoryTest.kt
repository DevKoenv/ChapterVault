package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.DatabaseMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionConfigRepositoryTest {
    @TempDir
    lateinit var tempDir: Path
    private lateinit var repo: ExtensionConfigRepository

    @BeforeEach
    fun setup() {
        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)
        repo = ExtensionConfigRepository()
    }

    @Test
    fun `set and get a value`() =
        runBlocking {
            repo.set("ext.test", "api_key", "secret123")
            assertEquals("secret123", repo.get("ext.test", "api_key"))
        }

    @Test
    fun `get returns null for missing key`() =
        runBlocking {
            assertNull(repo.get("ext.test", "missing"))
        }

    @Test
    fun `set overwrites existing value`() =
        runBlocking {
            repo.set("ext.test", "key", "old")
            repo.set("ext.test", "key", "new")
            assertEquals("new", repo.get("ext.test", "key"))
        }

    @Test
    fun `getAll returns all keys for extension`() =
        runBlocking {
            repo.set("ext.test", "k1", "v1")
            repo.set("ext.test", "k2", "v2")
            repo.set("other.ext", "k1", "other")
            val all = repo.getAll("ext.test")
            assertEquals(mapOf("k1" to "v1", "k2" to "v2"), all)
        }

    @Test
    fun `setAll sets multiple keys atomically`() =
        runBlocking {
            repo.setAll("ext.test", mapOf("k1" to "v1", "k2" to "v2"))
            assertEquals("v1", repo.get("ext.test", "k1"))
            assertEquals("v2", repo.get("ext.test", "k2"))
        }

    @Test
    fun `forExtension returns live values`() =
        runBlocking {
            val config = repo.forExtension("ext.test")
            assertNull(config.get("key"))
            repo.set("ext.test", "key", "value")
            assertEquals("value", config.get("key"))
        }
}
