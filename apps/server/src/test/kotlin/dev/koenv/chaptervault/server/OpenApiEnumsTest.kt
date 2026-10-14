package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The documented enums must list exactly the values the server can send. */
class OpenApiEnumsTest {
    private val enums: List<Set<String>> by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/openapi/documentation.yaml")) { "openapi/documentation.yaml is missing" }
                .use { it.readBytes().decodeToString() }
        Regex("""enum: \[([^\]]*)]""")
            .findAll(text)
            .map { m ->
                m.groupValues[1]
                    .split(",")
                    .map { it.trim() }
                    .toSet()
            }.toList()
    }

    private fun documentedFor(marker: String): List<Set<String>> = enums.filter { marker in it }

    @Test
    fun `task types in the document are the task types of the server`() {
        val documented = documentedFor("FETCH_CHAPTERS")
        assertTrue(documented.isNotEmpty(), "no documented enum mentions FETCH_CHAPTERS")
        documented.forEach { assertEquals(TaskType.entries.map { t -> t.name }.toSet(), it) }
    }

    @Test
    fun `series statuses in the document are the statuses of the server`() {
        val documented = documentedFor("IN_LIBRARY")
        assertTrue(documented.isNotEmpty(), "no documented enum mentions IN_LIBRARY")
        documented.forEach { assertEquals(SeriesStatus.entries.map { s -> s.name }.toSet(), it) }
    }

    @Test
    fun `extension sources in the document are the sources of the server`() {
        val documented = documentedFor("BUNDLED")
        assertTrue(documented.isNotEmpty(), "no documented enum mentions BUNDLED")
        documented.forEach { assertEquals(ExtensionSource.entries.map { s -> s.name }.toSet(), it) }
    }
}
