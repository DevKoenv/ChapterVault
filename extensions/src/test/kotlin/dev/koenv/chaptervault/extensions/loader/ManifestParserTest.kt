package dev.koenv.chaptervault.extensions.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManifestParserTest {
    private val validYaml =
        """
        id: "dev.example.my-source"
        name: "My Source"
        version: "1.2.0"
        minServerVersion: "1.0.0"
        description: "Fetches manga from example.com"
        author: "Test Author"
        capabilities:
          - connector
        entryPoint: "dev.example.MyExtension"
        """.trimIndent()

    @Test
    fun `parses valid manifest`() {
        val manifest = ManifestParser.parse(validYaml)
        assertNotNull(manifest)
        assertEquals("dev.example.my-source", manifest.id)
        assertEquals("My Source", manifest.name)
        assertEquals("1.2.0", manifest.version)
        assertEquals("1.0.0", manifest.minServerVersion)
        assertEquals(listOf("connector"), manifest.capabilities)
        assertEquals("dev.example.MyExtension", manifest.entryPoint)
    }

    @Test
    fun `uses default priority when not specified`() {
        val manifest = ManifestParser.parse(validYaml)
        assertNotNull(manifest)
        assertEquals(100, manifest.priority)
    }

    @Test
    fun `parses explicit priority`() {
        val yaml = validYaml + "\npriority: 50"
        val manifest = ManifestParser.parse(yaml)
        assertNotNull(manifest)
        assertEquals(50, manifest.priority)
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(ManifestParser.parse(""))
    }

    @Test
    fun `returns null for whitespace-only input`() {
        assertNull(ManifestParser.parse("   "))
    }

    @Test
    fun `returns null when id is missing`() {
        val yaml =
            """
            name: "Test"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "Test"
            author: "Test"
            capabilities: []
            entryPoint: "dev.example.Test"
            """.trimIndent()
        assertNull(ManifestParser.parse(yaml))
    }

    @Test
    fun `returns null when entryPoint is missing`() {
        val yaml =
            """
            id: "dev.example.test"
            name: "Test"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "Test"
            author: "Test"
            capabilities: []
            """.trimIndent()
        assertNull(ManifestParser.parse(yaml))
    }

    @Test
    fun `returns null for malformed yaml`() {
        assertNull(ManifestParser.parse("{{not: yaml: at all"))
    }

    @Test
    fun `parses multiple capabilities`() {
        val yaml =
            validYaml.replace(
                "capabilities:\n  - connector",
                "capabilities:\n  - connector\n  - metadata_enricher",
            )
        val manifest = ManifestParser.parse(yaml)
        assertNotNull(manifest)
        assertEquals(listOf("connector", "metadata_enricher"), manifest.capabilities)
    }

    @Test
    fun `returns null when capabilities is missing`() {
        val yaml =
            """
            id: "test.extension"
            name: "Test"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "desc"
            author: "Author"
            entryPoint: "test.Extension"
            """.trimIndent()
        assertNull(ManifestParser.parse(yaml))
    }

    @Test
    fun `returns null when description is missing`() {
        val yaml =
            """
            id: "test.extension"
            name: "Test"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            author: "Author"
            capabilities: []
            entryPoint: "test.Extension"
            """.trimIndent()
        assertNull(ManifestParser.parse(yaml))
    }

    @Test
    fun `returns null when author is missing`() {
        val yaml =
            """
            id: "test.extension"
            name: "Test"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "desc"
            capabilities: []
            entryPoint: "test.Extension"
            """.trimIndent()
        assertNull(ManifestParser.parse(yaml))
    }
}
