package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.PasswordPolicy
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

class UserRepository : AuthApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun register(
        credentials: Credentials,
        role: Role,
    ): Result<UserPrincipal> =
        dbQuery {
            val username = credentials.username.trim()
            usernameViolation(username)?.let {
                return@dbQuery Result.Failure(AppError.ValidationError(it))
            }
            PasswordPolicy.violation(credentials.password)?.let {
                return@dbQuery Result.Failure(AppError.ValidationError(it))
            }
            val existing =
                UserTable
                    .selectAll()
                    .where { sameUsername(username) }
                    .singleOrNull()
            if (existing != null) {
                return@dbQuery Result.Failure(AppError.Conflict("Username '$username' already taken"))
            }
            val id = Id.generate()
            val hash = BCrypt.hashpw(credentials.password, BCrypt.gensalt())
            val now = Instant.now()
            try {
                UserTable.insert {
                    it[UserTable.id] = id.toString()
                    it[UserTable.username] = username
                    it[UserTable.passwordHash] = hash
                    it[UserTable.roles] = role.name
                    it[UserTable.createdAt] = now.toKotlinInstant()
                }
            } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                return@dbQuery Result.Failure(AppError.Conflict("Username '$username' already taken"))
            }
            Result.Success(UserPrincipal(id = id, username = username, roles = setOf(role), createdAt = now))
        }

    override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> =
        dbQuery {
            val row =
                findForLogin(credentials)
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized())

            val principal = row.toPrincipal()
            val session = createSession(principal.id)
            Result.Success(principal to session)
        }

    override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> =
        dbQuery {
            val row =
                findForLogin(credentials)
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized())

            Result.Success(row.toPrincipal())
        }

    override suspend fun validateSession(token: String): Result<UserPrincipal> =
        dbQuery {
            val tokenHash = sessionTokenHash(token)
            val sessionRow =
                SessionTable
                    .selectAll()
                    .where { SessionTable.tokenHash eq tokenHash }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized("Invalid or expired session"))

            val now = Instant.now()
            val expiresAt = sessionRow[SessionTable.expiresAt].toJavaInstant()
            if (expiresAt.isBefore(now)) {
                SessionTable.deleteWhere { SessionTable.tokenHash eq tokenHash }
                return@dbQuery Result.Failure(AppError.Unauthorized("Session expired"))
            }
            // a session that is still being used does not run out; extended at most once a day to spare the database
            if (expiresAt.isBefore(now.plusSeconds(SESSION_TTL_SECONDS - SESSION_EXTEND_AFTER_SECONDS))) {
                SessionTable.update({ SessionTable.tokenHash eq tokenHash }) {
                    it[SessionTable.expiresAt] = now.plusSeconds(SESSION_TTL_SECONDS).toKotlinInstant()
                }
            }

            val userRow =
                UserTable
                    .selectAll()
                    .where { UserTable.id eq sessionRow[SessionTable.userId] }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized())

            Result.Success(userRow.toPrincipal())
        }

    override suspend fun invalidateSession(token: String): Result<Unit> =
        dbQuery {
            SessionTable.deleteWhere { SessionTable.tokenHash eq sessionTokenHash(token) }
            Result.Success(Unit)
        }

    override suspend fun listUsers(): Result<List<UserPrincipal>> =
        dbQuery {
            Result.Success(
                UserTable
                    .selectAll()
                    .orderBy(UserTable.createdAt, SortOrder.ASC)
                    .map { it.toPrincipal() },
            )
        }

    override suspend fun deleteUser(id: Id): Result<Unit> =
        dbQuery {
            val row =
                UserTable.selectAll().where { UserTable.id eq id.toString() }.singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("User", id.toString()))
            if (Role.ADMIN in row.rolesOf() && adminCount() <= 1) {
                return@dbQuery Result.Failure(AppError.Conflict(LAST_ADMIN_MESSAGE))
            }
            // sessions reference users without ON DELETE CASCADE, so they have to go first
            SessionTable.deleteWhere { SessionTable.userId eq id.toString() }
            UserTable.deleteWhere { UserTable.id eq id.toString() }
            Result.Success(Unit)
        }

    override suspend fun updateUserRole(
        id: Id,
        role: Role,
    ): Result<UserPrincipal> =
        dbQuery {
            val current =
                UserTable.selectAll().where { UserTable.id eq id.toString() }.singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("User", id.toString()))
            if (Role.ADMIN in current.rolesOf() && role != Role.ADMIN && adminCount() <= 1) {
                return@dbQuery Result.Failure(AppError.Conflict(LAST_ADMIN_MESSAGE))
            }
            val updated =
                UserTable.update({ UserTable.id eq id.toString() }) {
                    it[UserTable.roles] = role.name
                }
            if (updated == 0) return@dbQuery Result.Failure(AppError.NotFound("User", id.toString()))
            // invalidate existing sessions so the new role takes effect immediately
            SessionTable.deleteWhere { SessionTable.userId eq id.toString() }
            val row = UserTable.selectAll().where { UserTable.id eq id.toString() }.single()
            Result.Success(row.toPrincipal())
        }

    override suspend fun changePassword(
        userId: Id,
        currentPassword: String,
        newPassword: String,
    ): Result<Unit> =
        dbQuery {
            val row =
                UserTable
                    .selectAll()
                    .where { UserTable.id eq userId.toString() }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("User", userId.toString()))
            if (!BCrypt.checkpw(currentPassword, row[UserTable.passwordHash])) {
                return@dbQuery Result.Failure(AppError.Unauthorized("Current password is incorrect"))
            }
            PasswordPolicy.violation(newPassword)?.let {
                return@dbQuery Result.Failure(AppError.ValidationError(it))
            }
            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            UserTable.update({ UserTable.id eq userId.toString() }) {
                it[UserTable.passwordHash] = newHash
            }
            // a stolen or forgotten token must not survive a password change
            SessionTable.deleteWhere { SessionTable.userId eq userId.toString() }
            Result.Success(Unit)
        }

    override suspend fun cleanupExpiredSessions(): Result<Unit> =
        dbQuery {
            SessionTable.deleteWhere { SessionTable.expiresAt less Instant.now().toKotlinInstant() }
            Result.Success(Unit)
        }

    private fun Transaction.createSession(userId: Id): Session {
        val sessionId = Id.generate()
        val token = generateToken()
        val expiresAt = Instant.now().plusSeconds(SESSION_TTL_SECONDS)
        SessionTable.insert {
            it[SessionTable.id] = sessionId.toString()
            it[SessionTable.userId] = userId.toString()
            it[SessionTable.tokenHash] = sessionTokenHash(token)
            it[SessionTable.expiresAt] = expiresAt.toKotlinInstant()
            it[SessionTable.createdAt] = Instant.now().toKotlinInstant()
        }
        // a user who logs in over and over (or a script that never logs out) does not pile up sessions
        val surplus =
            SessionTable
                .selectAll()
                .where { SessionTable.userId eq userId.toString() }
                .orderBy(SessionTable.createdAt, SortOrder.DESC)
                .limit(Int.MAX_VALUE, MAX_SESSIONS_PER_USER.toLong())
                .map { it[SessionTable.id] }
        if (surplus.isNotEmpty()) SessionTable.deleteWhere { SessionTable.id inList surplus }
        return Session(id = sessionId, userId = userId, token = token, expiresAt = expiresAt)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sameUsername(username: String): Op<Boolean> = UserTable.username.lowerCase() eq stringParam(username).lowerCase()

    private fun usernameViolation(username: String): String? =
        when {
            username.isEmpty() -> "Username must not be blank"
            username.length > MAX_USERNAME_LENGTH -> "Username must be at most $MAX_USERNAME_LENGTH characters"
            // control and format characters (line breaks, NUL, right-to-left override) make names that look like others
            username.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } ->
                "Username must not contain control characters"
            else -> null
        }

    /**
     * The user for a login attempt, or null when the name is unknown or the password is wrong. The password is checked
     * even for an unknown name (against a throwaway hash), so a failed login takes the same time either way and cannot
     * be used to find out which names exist.
     */
    private fun Transaction.findForLogin(credentials: Credentials): ResultRow? {
        val row =
            UserTable
                .selectAll()
                .where { sameUsername(credentials.username.trim()) }
                .singleOrNull()
        val matches = BCrypt.checkpw(credentials.password, row?.get(UserTable.passwordHash) ?: UNKNOWN_USER_HASH)
        return row?.takeIf { matches }
    }

    private fun ResultRow.rolesOf(): Set<Role> =
        this[UserTable.roles]
            .split(",")
            .map { Role.valueOf(it.trim()) }
            .toSet()

    private fun Transaction.adminCount(): Int = UserTable.selectAll().count { Role.ADMIN in it.rolesOf() }

    private fun ResultRow.toPrincipal(): UserPrincipal =
        UserPrincipal(
            id = Id.from(this[UserTable.id]),
            username = this[UserTable.username],
            roles = rolesOf(),
            createdAt = this[UserTable.createdAt].toJavaInstant(),
        )

    companion object {
        private const val LAST_ADMIN_MESSAGE = "Cannot remove the last administrator"
        private const val SESSION_TTL_SECONDS = 30L * 24 * 3600 // 30 days
        private const val SESSION_EXTEND_AFTER_SECONDS = 24L * 3600
        private const val MAX_SESSIONS_PER_USER = 20
        private const val MAX_USERNAME_LENGTH = 100
        private val UNKNOWN_USER_HASH: String = BCrypt.hashpw("not-a-real-password", BCrypt.gensalt())
        private val secureRandom = SecureRandom()
    }
}

/** What is stored in `sessions.token_hash`: SHA-256 of the bearer token, as hex. The token itself is never kept. */
internal fun sessionTokenHash(token: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
