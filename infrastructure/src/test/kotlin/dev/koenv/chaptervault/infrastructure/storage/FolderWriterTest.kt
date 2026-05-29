package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class FolderWriterTest {
    private val writer = FolderWriter()

    @Test
    fun `write creates three files at the destination path`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("folder-writer-test")
            val destination = tempDir.resolve("chapter1")
            val pages =
                listOf(
                    Page(1, byteArrayOf(1, 2, 3)),
                    Page(2, byteArrayOf(4, 5, 6)),
                    Page(3, byteArrayOf(7, 8, 9)),
                )

            writer.write(pages, destination)

            assertTrue(Files.exists(destination.resolve("001.jpg")))
            assertTrue(Files.exists(destination.resolve("002.jpg")))
            assertTrue(Files.exists(destination.resolve("003.jpg")))
        }
    }

    @Test
    fun `write stores correct bytes for each page`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("folder-writer-test")
            val destination = tempDir.resolve("chapter2")
            val pages =
                listOf(
                    Page(1, byteArrayOf(10, 20, 30)),
                    Page(2, byteArrayOf(40, 50, 60)),
                    Page(3, byteArrayOf(70, 80, 90)),
                )

            writer.write(pages, destination)

            assertContentEquals(byteArrayOf(10, 20, 30), Files.readAllBytes(destination.resolve("001.jpg")))
            assertContentEquals(byteArrayOf(40, 50, 60), Files.readAllBytes(destination.resolve("002.jpg")))
            assertContentEquals(byteArrayOf(70, 80, 90), Files.readAllBytes(destination.resolve("003.jpg")))
        }
    }
}
