package dev.koenv.chaptervault.shared

import dev.koenv.chaptervault.shared.result.AppError
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AppErrorTest {
    @Test
    fun `NotFound message includes resource and id`() {
        val error = AppError.NotFound("Series", "abc-123")
        assertContains(error.message, "Series")
        assertContains(error.message, "abc-123")
    }

    @Test
    fun `ValidationError message passthrough`() {
        val error = AppError.ValidationError("name must not be blank")
        assertEquals("name must not be blank", error.message)
    }

    @Test
    fun `Conflict message passthrough`() {
        val error = AppError.Conflict("already exists")
        assertEquals("already exists", error.message)
    }

    @Test
    fun `InternalError message passthrough`() {
        val error = AppError.InternalError("unexpected failure")
        assertEquals("unexpected failure", error.message)
    }

    @Test
    fun `InternalError retains cause`() {
        val cause = RuntimeException("root cause")
        val error = AppError.InternalError("wrapped", cause)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `Unauthorized has default message`() {
        val error = AppError.Unauthorized()
        assertContains(error.message, "Unauthorized")
    }

    @Test
    fun `Forbidden has default message`() {
        val error = AppError.Forbidden()
        assertContains(error.message, "Forbidden")
    }
}
