package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.DatabaseMigrations
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionRegistryRepositoryTest {
    @TempDir lateinit var tempDir: File
    private lateinit var repo: ExtensionRegistryRepository

    @BeforeEach
    fun setup() {
        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("test.db").absolutePath}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)
        repo = ExtensionRegistryRepository()
    }

    @Test
    fun `create and find registry`() {
        val created = repo.create("Official", "https://example.com/registry.json")
        assertEquals("Official", created.name)
        assertEquals("https://example.com/registry.json", created.url)
        assertTrue(created.enabled)
        val found = repo.findById(created.id)
        assertEquals(created, found)
    }

    @Test
    fun `list returns all registries`() {
        repo.create("A", "https://a.com/index.json")
        repo.create("B", "https://b.com/index.json")
        assertEquals(2, repo.list().size)
    }

    @Test
    fun `delete removes registry`() {
        val r = repo.create("Temp", "https://temp.com/index.json")
        repo.delete(r.id)
        assertNull(repo.findById(r.id))
    }

    @Test
    fun `setEnabled toggles enabled`() {
        val r = repo.create("Test", "https://t.com/index.json")
        repo.setEnabled(r.id, false)
        assertEquals(false, repo.findById(r.id)?.enabled)
    }
}
