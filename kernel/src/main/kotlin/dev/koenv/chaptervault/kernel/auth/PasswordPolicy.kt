package dev.koenv.chaptervault.kernel.auth

/**
 * Rules for new passwords. The maximum is a byte limit because BCrypt only looks at the first 72 bytes: longer
 * passwords would be accepted but silently truncated.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_BYTES = 72

    /** Returns a message describing the violated rule, or null when [password] is acceptable. */
    fun violation(password: String): String? =
        when {
            password.length < MIN_LENGTH -> "Password must be at least $MIN_LENGTH characters"
            password.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "Password must be at most $MAX_BYTES bytes"
            else -> null
        }
}
