package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.auth.PasswordGenerator
import dev.koenv.chaptervault.shared.result.Result
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Recovery when nobody can log in: put a username in `<data dir>/reset-password` and restart. That user gets a new
 * generated password, printed once in the log, and is signed out everywhere. The file is removed before anything
 * changes, so a reset happens once even when the server keeps restarting.
 */
internal class PasswordResetFile(
    private val auth: AuthApi,
    private val file: Path,
    private val announce: (String) -> Unit = { logger.warn { it } },
    private val warn: (String) -> Unit = { logger.error { it } },
    private val remove: (Path) -> Unit = { Files.delete(it) },
) {
    suspend fun run() {
        if (!Files.isRegularFile(file)) return
        val username =
            try {
                Files.readString(file).trim().also { remove(file) }
            } catch (e: IOException) {
                warn("Found $file but could not read or remove it (${e.message}); no password was reset. Remove it by hand.")
                return
            }
        val users = auth.listUsers()
        if (users is Result.Failure) {
            warn("Password reset for '$username' failed: ${users.error.message}")
            return
        }
        val user = (users as Result.Success).value.firstOrNull { it.username.equals(username, ignoreCase = true) }
        if (user == null) {
            warn("$file named the user '$username', who does not exist; no password was reset")
            return
        }
        val password = PasswordGenerator.generate()
        when (val result = auth.setPassword(user.id, password)) {
            is Result.Failure -> warn("Password reset for '${user.username}' failed: ${result.error.message}")
            is Result.Success -> announce(banner(user.username, password))
        }
    }

    private fun banner(
        username: String,
        password: String,
    ): String =
        """
        |
        |==========================================================================
        | ChapterVault reset the password of '$username' (requested by reset-password).
        | username: $username
        | password: $password
        | This password is shown only once. Log in and change it.
        | The user was signed out everywhere.
        |==========================================================================
        """.trimMargin()
}
