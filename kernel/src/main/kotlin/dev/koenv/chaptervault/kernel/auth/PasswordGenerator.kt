package dev.koenv.chaptervault.kernel.auth

import java.security.SecureRandom

/** Random passwords without look-alike characters (0/O, 1/l/I), so one can be typed from a log line or a response. */
object PasswordGenerator {
    private const val LENGTH = 24
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun generate(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
