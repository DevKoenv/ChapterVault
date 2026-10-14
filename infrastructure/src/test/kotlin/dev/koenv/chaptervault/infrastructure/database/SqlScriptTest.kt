package dev.koenv.chaptervault.infrastructure.database

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlScriptTest {
    @Test
    fun `statements are split on semicolons`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScript.split("SELECT 1;\nSELECT 2;\n"))
    }

    @Test
    fun `a last statement without a semicolon is kept`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScript.split("SELECT 1;\nSELECT 2"))
    }

    @Test
    fun `comments and blank lines are dropped`() {
        val script = "-- header\n\nSELECT 1; -- trailing note\n-- between\nSELECT 2;\n"

        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScript.split(script))
    }

    @Test
    fun `a semicolon inside a string literal does not end the statement`() {
        assertEquals(listOf("INSERT INTO t VALUES ('a;b')", "SELECT 1"), SqlScript.split("INSERT INTO t VALUES ('a;b');\nSELECT 1;"))
    }

    @Test
    fun `a doubled quote stays inside the literal`() {
        assertEquals(listOf("INSERT INTO t VALUES ('it''s; fine')"), SqlScript.split("INSERT INTO t VALUES ('it''s; fine');"))
    }

    @Test
    fun `a double dash inside a literal is not a comment`() {
        assertEquals(listOf("INSERT INTO t VALUES ('a -- b')"), SqlScript.split("INSERT INTO t VALUES ('a -- b');"))
    }

    @Test
    fun `multi line statements keep their line breaks`() {
        assertEquals(listOf("CREATE TABLE t (\n    id INTEGER\n)"), SqlScript.split("CREATE TABLE t (\n    id INTEGER\n);"))
    }

    @Test
    fun `line endings do not change the result`() {
        assertEquals(SqlScript.split("SELECT 1;\nSELECT 2;\n"), SqlScript.split("SELECT 1;\r\nSELECT 2;\r\n"))
    }
}
