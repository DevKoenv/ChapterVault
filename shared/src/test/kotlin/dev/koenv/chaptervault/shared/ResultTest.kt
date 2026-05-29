package dev.koenv.chaptervault.shared

import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.result.getOrElse
import dev.koenv.chaptervault.shared.result.map
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResultTest {
    @Test
    fun `Success holds value`() {
        val result = Result.Success(42)
        assertEquals(42, result.value)
    }

    @Test
    fun `Failure holds error`() {
        val error = AppError.NotFound("Series", "123")
        val result = Result.Failure(error)
        assertEquals(error, result.error)
    }

    @Test
    fun `map transforms Success value`() {
        val result = Result.Success(2).map { it * 3 }
        assertIs<Result.Success<Int>>(result)
        assertEquals(6, result.value)
    }

    @Test
    fun `map passes Failure through unchanged`() {
        val error = AppError.InternalError("boom")
        val result: Result<Int> = Result.Failure(error)
        val mapped = result.map { it * 3 }
        assertIs<Result.Failure>(mapped)
        assertEquals(error, mapped.error)
    }

    @Test
    fun `getOrElse returns value on Success`() {
        val value = Result.Success("hello").getOrElse { "fallback" }
        assertEquals("hello", value)
    }

    @Test
    fun `getOrElse returns default on Failure`() {
        val value = Result.Failure(AppError.InternalError("err")).getOrElse { "fallback" }
        assertEquals("fallback", value)
    }

    @Test
    fun `getOrElse receives the error in the default lambda`() {
        val error = AppError.NotFound("Chapter", "abc")
        var received: AppError? = null
        Result.Failure(error).getOrElse {
            received = it
            "x"
        }
        assertEquals(error, received)
    }
}
