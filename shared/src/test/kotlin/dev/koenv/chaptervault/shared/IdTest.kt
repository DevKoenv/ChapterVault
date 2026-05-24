package dev.koenv.chaptervault.shared

import dev.koenv.chaptervault.shared.utils.Id
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdTest {
    @Test
    fun `from parses valid UUID string`() {
        val uuidStr = "00000000-0000-0000-0000-000000000001"
        val id = Id.from(uuidStr)
        assertEquals(uuidStr, id.toString())
    }

    @Test
    fun `from throws on invalid string`() {
        assertFailsWith<IllegalArgumentException> { Id.from("not-a-uuid") }
    }

    @Test
    fun `generate produces unique IDs`() {
        val a = Id.generate()
        val b = Id.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun `two Ids from same string are equal`() {
        val str = "12345678-1234-1234-1234-123456789abc"
        assertEquals(Id.from(str), Id.from(str))
    }

    @Test
    fun `toString returns UUID string`() {
        val str = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertEquals(str, Id.from(str).toString())
    }
}
