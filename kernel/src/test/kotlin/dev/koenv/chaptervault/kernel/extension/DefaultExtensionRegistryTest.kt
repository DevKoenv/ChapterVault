package dev.koenv.chaptervault.kernel.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class FakeExtension(
    override val id: String,
    override val name: String = "Fake",
    override val version: String = "1.0.0",
    override val capabilities: Set<Capability> = emptySet(),
) : Extension

class DefaultExtensionRegistryTest {
    private val registry = DefaultExtensionRegistry()

    @Test
    fun `register makes extension visible via all()`() {
        val ext = FakeExtension("ext-1")
        registry.register(ext)
        assertTrue(registry.all().any { it.id == "ext-1" })
    }

    @Test
    fun `findById returns extension by ID after register`() {
        val ext = FakeExtension("ext-1")
        registry.register(ext)
        assertEquals(ext, registry.findById("ext-1"))
    }

    @Test
    fun `findById returns null for unknown ID`() {
        assertNull(registry.findById("does-not-exist"))
    }

    @Test
    fun `withCapability returns extensions that declare that capability`() {
        val ext = FakeExtension("ext-1", capabilities = setOf(Capability.CanFetchSeries))
        registry.register(ext)
        val result = registry.withCapability(Capability.CanFetchSeries)
        assertTrue(result.any { it.id == "ext-1" })
    }

    @Test
    fun `withCapability returns empty list for capability no extension has`() {
        registry.register(FakeExtension("ext-1", capabilities = setOf(Capability.CanFetchSeries)))
        assertTrue(registry.withCapability(Capability.CanDownloadChapters).isEmpty())
    }

    @Test
    fun `extension with multiple capabilities appears in withCapability for each`() {
        val ext =
            FakeExtension(
                "ext-multi",
                capabilities = setOf(Capability.CanFetchSeries, Capability.CanDownloadChapters),
            )
        registry.register(ext)
        assertTrue(registry.withCapability(Capability.CanFetchSeries).any { it.id == "ext-multi" })
        assertTrue(registry.withCapability(Capability.CanDownloadChapters).any { it.id == "ext-multi" })
    }

    @Test
    fun `registering two extensions all() returns both`() {
        registry.register(FakeExtension("ext-1"))
        registry.register(FakeExtension("ext-2"))
        val ids = registry.all().map { it.id }
        assertTrue(ids.containsAll(listOf("ext-1", "ext-2")))
    }

    @Test
    fun `registering extension with same ID twice second overwrites first in findById`() {
        val original = FakeExtension("ext-1", name = "Original")
        val replacement = FakeExtension("ext-1", name = "Replacement")
        registry.register(original)
        registry.register(replacement)
        assertEquals("Replacement", registry.findById("ext-1")?.name)
    }
}
