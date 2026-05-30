package dev.koenv.chaptervault.kernel.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultExtensionRegistryTest {
    private fun makeEntry(
        id: String,
        status: ExtensionStatus = ExtensionStatus.ENABLED,
        source: ExtensionSource = ExtensionSource.BUNDLED,
    ): ExtensionEntry {
        val ext =
            object : Extension {
                override val id = id
                override val name = id
                override val version = "1.0.0"

                override fun capabilities() = emptySet<Capability>()

                override fun onEnable(context: ExtensionContext) {}

                override fun onDisable() {}
            }
        return ExtensionEntry(extension = ext, status = status, source = source)
    }

    @Test
    fun `register and find by id`() {
        val registry = DefaultExtensionRegistry()
        val entry = makeEntry("test.ext")
        registry.register(entry)
        assertNotNull(registry.findById("test.ext"))
        assertEquals(ExtensionStatus.ENABLED, registry.findById("test.ext")!!.status)
    }

    @Test
    fun `all returns all registered entries`() {
        val registry = DefaultExtensionRegistry()
        registry.register(makeEntry("a"))
        registry.register(makeEntry("b"))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `updateStatus changes status and errorMessage`() {
        val registry = DefaultExtensionRegistry()
        registry.register(makeEntry("a", ExtensionStatus.LOADING))
        registry.updateStatus("a", ExtensionStatus.FAILED, "bad manifest")
        val entry = registry.findById("a")!!
        assertEquals(ExtensionStatus.FAILED, entry.status)
        assertEquals("bad manifest", entry.errorMessage)
    }

    @Test
    fun `unregister removes the entry`() {
        val registry = DefaultExtensionRegistry()
        registry.register(makeEntry("a"))
        registry.unregister("a")
        assertNull(registry.findById("a"))
    }

    @Test
    fun `enabledWithCapability returns only ENABLED entries with that capability`() {
        val registry = DefaultExtensionRegistry()
        val enabledExt =
            object : Extension {
                override val id = "enabled"
                override val name = "enabled"
                override val version = "1.0.0"

                override fun capabilities() = setOf<Capability>(Capability.CanFetchSeries)

                override fun onEnable(context: ExtensionContext) {}

                override fun onDisable() {}
            }
        val disabledExt =
            object : Extension {
                override val id = "disabled"
                override val name = "disabled"
                override val version = "1.0.0"

                override fun capabilities() = setOf<Capability>(Capability.CanFetchSeries)

                override fun onEnable(context: ExtensionContext) {}

                override fun onDisable() {}
            }
        registry.register(ExtensionEntry(enabledExt, ExtensionStatus.ENABLED, ExtensionSource.BUNDLED))
        registry.register(ExtensionEntry(disabledExt, ExtensionStatus.DISABLED, ExtensionSource.BUNDLED))
        val results = registry.enabledWithCapability(Capability.CanFetchSeries)
        assertEquals(1, results.size)
        assertEquals("enabled", results[0].extension.id)
    }
}
