package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalExtensionLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    private fun buildJar(manifestYaml: String, vararg classes: Class<*>): Path {
        val jar = Files.createTempFile(tempDir, "test-ext", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            jos.putNextEntry(JarEntry("extension.yaml"))
            jos.write(manifestYaml.toByteArray())
            jos.closeEntry()
            for (cls in classes) {
                val resourcePath = cls.name.replace('.', '/') + ".class"
                val bytes = cls.getResourceAsStream("/$resourcePath")!!.readBytes()
                jos.putNextEntry(JarEntry(resourcePath))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return jar
    }

    private fun makeLoader(serverVersion: String = "1.0.0") = ExternalExtensionLoader(
        extensionsDir = tempDir,
        serverVersion = serverVersion,
        parentClassLoader = Thread.currentThread().contextClassLoader,
    )

    @Test
    fun `returns empty list when directory is empty`() {
        val results = makeLoader().loadAll()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `skips JAR with missing extension yaml`() {
        val jar = Files.createTempFile(tempDir, "no-manifest", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { } // empty JAR
        val results = makeLoader().loadAll()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `skips JAR with unparseable manifest`() {
        buildJar("{{not valid yaml")
        val results = makeLoader().loadAll()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `skips JAR when minServerVersion is higher than server version`() {
        val manifest = """
            id: "dev.test.future"
            name: "Future"
            version: "1.0.0"
            minServerVersion: "2.0.0"
            description: "Needs v2"
            author: "Test"
            capabilities:
              - connector
            entryPoint: "dev.test.FakeExtension"
        """.trimIndent()
        buildJar(manifest)
        val results = makeLoader(serverVersion = "1.0.0").loadAll()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `accepts JAR when minServerVersion equals server version`() {
        val manifest = """
            id: "dev.koenv.chaptervault.extensions.loader.testextension"
            name: "Test Extension"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "Test"
            author: "Test"
            capabilities:
              - connector
            entryPoint: "dev.koenv.chaptervault.extensions.loader.TestExtension"
        """.trimIndent()
        buildJar(manifest, TestExtension::class.java)
        val results = makeLoader(serverVersion = "1.0.0").loadAll()
        assertEquals(1, results.size)
    }

    @Test
    fun `accepts JAR when server is a higher minor version than minServerVersion`() {
        val manifest = """
            id: "dev.koenv.chaptervault.extensions.loader.testextension"
            name: "Test Extension"
            version: "1.0.0"
            minServerVersion: "1.0.1"
            description: "Test"
            author: "Test"
            capabilities:
              - connector
            entryPoint: "dev.koenv.chaptervault.extensions.loader.TestExtension"
        """.trimIndent()
        buildJar(manifest, TestExtension::class.java)
        val results = makeLoader(serverVersion = "1.1.0").loadAll()
        assertEquals(1, results.size)
    }

    @Test
    fun `skips JAR when entry point class is not found`() {
        val manifest = """
            id: "dev.test.missing-class"
            name: "Missing Class"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "Test"
            author: "Test"
            capabilities:
              - connector
            entryPoint: "dev.test.NonExistentClass"
        """.trimIndent()
        buildJar(manifest)
        val results = makeLoader().loadAll()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `loads extension from valid JAR`() {
        val manifest = """
            id: "dev.koenv.chaptervault.extensions.loader.testextension"
            name: "Test Extension"
            version: "1.0.0"
            minServerVersion: "1.0.0"
            description: "Test"
            author: "Test"
            capabilities:
              - connector
            entryPoint: "dev.koenv.chaptervault.extensions.loader.TestExtension"
        """.trimIndent()
        buildJar(manifest, TestExtension::class.java)
        val results = makeLoader().loadAll()
        assertEquals(1, results.size)
        assertEquals("dev.koenv.chaptervault.extensions.loader.testextension", results[0].first.id)
    }

    @Test
    fun `ignores non-jar files in directory`() {
        Files.writeString(tempDir.resolve("readme.txt"), "not a jar")
        val results = makeLoader().loadAll()
        assertTrue(results.isEmpty())
    }
}

// Minimal test extension -- must be in the same module so its class bytes are accessible
class TestExtension : Extension {
    override val id = "dev.koenv.chaptervault.extensions.loader.testextension"
    override val name = "Test Extension"
    override val version = "1.0.0"
    override fun capabilities() = emptySet<Capability>()
    override fun onEnable(context: ExtensionContext) {}
    override fun onDisable() {}
}
