package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.PasswordGenerator
import dev.koenv.chaptervault.kernel.auth.PasswordPolicy
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.Result
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

internal fun generateAdminPassword(): String = PasswordGenerator.generate()

/**
 * Creates the first administrator, and only on an install that has no users at all. Re-creating a deleted `admin`
 * on every boot would hand a known account back to whoever removed it, so an existing user table is never touched.
 *
 * The password is `CHAPTERVAULT_ADMIN_PASS` when set (and acceptable), otherwise a random one that is announced once.
 */
internal class AdminBootstrap(
    private val auth: AuthApi,
    private val env: (String) -> String? = System::getenv,
    private val announce: (String) -> Unit = { logger.warn { it } },
) {
    suspend fun run() {
        val existing = auth.listUsers()
        if (existing is Result.Failure) error("Cannot check for existing users: ${existing.error.message}")
        if ((existing as Result.Success).value.isNotEmpty()) return

        val username = env("CHAPTERVAULT_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "admin"
        val configured = env("CHAPTERVAULT_ADMIN_PASS")
        if (configured != null) {
            PasswordPolicy.violation(configured)?.let { error("CHAPTERVAULT_ADMIN_PASS is not acceptable: $it") }
        }
        val password = configured ?: generateAdminPassword()

        when (val result = auth.register(Credentials(username, password), Role.ADMIN)) {
            is Result.Failure -> error("Cannot create the administrator account: ${result.error.message}")
            is Result.Success -> {
                if (configured == null) announce(banner(username, password)) else logger.info { "Created administrator '$username'" }
            }
        }
    }

    private fun banner(
        username: String,
        password: String,
    ): String =
        """
        |
        |==========================================================================
        | ChapterVault created the first administrator account.
        | username: $username
        | password: $password
        | This password is shown only once. Log in and change it, or set
        | CHAPTERVAULT_ADMIN_PASS before the first start to choose your own.
        |==========================================================================
        """.trimMargin()
}
