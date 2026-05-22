package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CbzWriterTest {
    private val writer = CbzWriter()

    @Test
    fun `write creates a zip file at the destination path`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("cbz-writer-test")
            val destination = tempDir.resolve("series1").resolve("chapter1.cbz")
            val pages = listOf(
                Page(1, byteArrayOf(1, 2, 3)),
                Page(2, byteArrayOf(4, 5, 6)),
                Page(3, byteArrayOf(7, 8, 9)),
            )

            writer.write(pages, destination)

            assertTrue(Files.exists(destination))
        }
    }

    @Test
    fun `write produces zip with three entries named by index`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("cbz-writer-test")
            val destination = tempDir.resolve("chapter1.cbz")
            val pages = listOf(
                Page(1, byteArrayOf(10, 20)),
                Page(2, byteArrayOf(30, 40)),
                Page(3, byteArrayOf(50, 60)),
            )

            writer.write(pages, destination)

            ZipFile(destination.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(3, names.size)
                assertTrue("001.jpg" in names)
                assertTrue("002.jpg" in names)
                assertTrue("003.jpg" in names)
            }
        }
    }
}
