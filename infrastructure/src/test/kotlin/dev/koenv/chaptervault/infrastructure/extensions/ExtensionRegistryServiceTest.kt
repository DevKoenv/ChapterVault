package dev.koenv.chaptervault.infrastructure.extensions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionRegistryServiceTest {
    @Test
    fun `merge catalogs from two registries`() {
        val catalog1 = RegistryCatalog(
            schemaVersion = 1,
            registryName = "Official",
            extensions = listOf(
                CatalogEntry("com.example.a", "A", "1.0.0", jarUrl = "https://example.com/a.jar"),
            ),
        )
        val catalog2 = RegistryCatalog(
            schemaVersion = 1,
            registryName = "Community",
            extensions = listOf(
                CatalogEntry("com.example.b", "B", "2.0.0", jarUrl = "https://community.com/b.jar"),
            ),
        )
        val merged = ExtensionRegistryService.mergeCatalogs(listOf(catalog1, catalog2))
        assertEquals(2, merged.size)
        assertFalse(merged.any { it.conflicting })
    }

    @Test
    fun `conflicting id returns all entries flagged as conflicting`() {
        val catalog1 = RegistryCatalog(1, "R1", listOf(CatalogEntry("com.example.a", "A", "1.0.0", jarUrl = "https://r1.com/a.jar")))
        val catalog2 = RegistryCatalog(1, "R2", listOf(CatalogEntry("com.example.a", "A", "1.1.0", jarUrl = "https://r2.com/a.jar")))
        val merged = ExtensionRegistryService.mergeCatalogs(listOf(catalog1, catalog2))
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.conflicting })
    }

    @Test
    fun `install throws on conflicting extension`() {
        assertFailsWith<ConflictingExtensionException> {
            ExtensionRegistryService.requireNonConflicting("com.example.a", listOf(
                ResolvedCatalogEntry(CatalogEntry("com.example.a", "A", "1.0.0", jarUrl = "https://r1.com/a.jar"), "R1", conflicting = true),
            ))
        }
    }
}
