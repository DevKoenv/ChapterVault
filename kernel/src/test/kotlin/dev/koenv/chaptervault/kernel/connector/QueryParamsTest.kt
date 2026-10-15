package dev.koenv.chaptervault.kernel.connector

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QueryParamsTest {
    @Test
    fun `scalar values are passed through as strings`() {
        val params = QueryParams.of("page" to 1, "limit" to 20, "active" to true)

        assertEquals(
            listOf("page" to "1", "limit" to "20", "active" to "true"),
            params.flatten(),
        )
    }

    @Test
    fun `lists serialize as repeated key brackets`() {
        val params = QueryParams.of("content_rating" to listOf("safe", "suggestive"))

        assertEquals(
            listOf("content_rating[]" to "safe", "content_rating[]" to "suggestive"),
            params.flatten(),
        )
    }

    @Test
    fun `nested maps serialize using bracket notation with dynamic keys`() {
        val sortField = "follows_total"
        val params = QueryParams.of("order" to mapOf(sortField to "desc"))

        assertEquals(listOf("order[follows_total]" to "desc"), params.flatten())
    }

    @Test
    fun `null values are omitted`() {
        val params = QueryParams.of("title" to null, "page" to 1)

        assertEquals(listOf("page" to "1"), params.flatten())
    }

    @Test
    fun `empty parameter collections flatten to an empty list`() {
        assertEquals(emptyList(), QueryParams.of().flatten())
    }

    @Test
    fun `plus operator adds a single entry immutably`() {
        val base = QueryParams.of("page" to 1)
        val extended = base + ("limit" to 20)

        assertEquals(listOf("page" to "1"), base.flatten())
        assertEquals(listOf("page" to "1", "limit" to "20"), extended.flatten())
    }

    @Test
    fun `plus operator adds a map of entries immutably`() {
        val base = QueryParams.of("page" to 1)
        val extended = base + mapOf("limit" to 20, "title" to "One Piece")

        assertEquals(listOf("page" to "1"), base.flatten())
        assertEquals(listOf("page" to "1", "limit" to "20", "title" to "One Piece"), extended.flatten())
    }

    @Test
    fun `toString renders a readable key-value string for logging`() {
        val params = QueryParams.of("keyword" to "One Piece", "content_rating" to listOf("safe", "suggestive"))

        assertEquals("keyword=One Piece&content_rating[]=safe&content_rating[]=suggestive", params.toString())
    }

    @Test
    fun `the full example from the connector spec flattens to the expected pairs`() {
        val sortField = "follows_total"
        val params =
            QueryParams.of(
                "keyword" to "One Piece",
                "content_rating" to listOf("safe", "suggestive"),
                "order" to mapOf(sortField to "desc"),
                "page" to 1,
                "limit" to 20,
            )

        assertEquals(
            listOf(
                "keyword" to "One Piece",
                "content_rating[]" to "safe",
                "content_rating[]" to "suggestive",
                "order[follows_total]" to "desc",
                "page" to "1",
                "limit" to "20",
            ),
            params.flatten(),
        )
    }
}
