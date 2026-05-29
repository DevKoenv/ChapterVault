package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.time.Instant

class UserRepository : AuthApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun register(
        credentials: Credentials,
        role: Role,
    ): Result<UserPrincipal> =
        dbQuery {
            val existing =
                UserTable
                    .selectAll()
                    .where { UserTable.username eq credentials.username }
                    .singleOrNull()
            if (existing != null) {
                return@dbQuery Result.Failure(AppError.Conflict("Username '${credentials.username}' already taken"))
            }
            val id = Id.generate()
            val hash = BCrypt.hashpw(credentials.password, BCrypt.gensalt())
            try {
                UserTable.insert {
                    it[UserTable.id] = id.toString()
                    it[UserTable.username] = credentials.username
                    it[UserTable.passwordHash] = hash
                    it[UserTable.roles] = role.name
                    it[UserTable.createdAt] = Instant.now().toKotlinInstant()
                }
            } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                return@dbQuery Result.Failure(AppError.Conflict("Username '${credentials.username}' already taken"))
            }
            Result.Success(UserPrincipal(id = id, username = credentials.username, roles = setOf(role)))
        }

    override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> =
        dbQuery {
            val row =
                UserTable
                    .selectAll()
                    .where { UserTable.username eq credentials.username }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized())

            if (!BCrypt.checkpw(credentials.password, row[UserTable.passwordHash])) {
                return@dbQuery Result.Failure(AppError.Unauthorized())
            }

            val principal = row.toPrincipal()
            val session = createSession(principal.id)
            Result.Success(principal to session)
        }

    override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> =
        dbQuery {
            val row =
                UserTable
                    .selectAll()
                    .where { UserTable.username eq credentials.username }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized())

            if (!BCrypt.checkpw(credentials.password, row[UserTable.passwordHash])) {
                return@dbQuery Result.Failure(AppError.Unauthorized())
            }

            Result.Success(row.toPrincipal())
        }

    override suspend fun validateSession(token: String): Result<UserPrincipal> =
        dbQuery {
            val sessionRow =
                SessionTable
                    .selectAll()
                    .where { SessionTable.token eq token }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.Unauthorized("Invalid or expired session"))

            if (sessionRow[SessionTable.expiresAt].toJavaInstant().isBefore(Instant.now())) {
                SessionTable.deleteWhere { SessionTable.token eq token }
                return@dbQuery Result.Failure(AppError.Unauthorized("Session expired"))
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
            SessionTable.deleteWhere { SessionTable.token eq token }
            Result.Success(Unit)
        }

    private fun Transaction.createSession(userId: Id): Session {
        val sessionId = Id.generate()
        val token = generateToken()
        val expiresAt = Instant.now().plusSeconds(SESSION_TTL_SECONDS)
        SessionTable.insert {
            it[SessionTable.id] = sessionId.toString()
            it[SessionTable.userId] = userId.toString()
            it[SessionTable.token] = token
            it[SessionTable.expiresAt] = expiresAt.toKotlinInstant()
            it[SessionTable.createdAt] = Instant.now().toKotlinInstant()
        }
        return Session(id = sessionId, userId = userId, token = token, expiresAt = expiresAt)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ResultRow.toPrincipal(): UserPrincipal {
        val roles =
            this[UserTable.roles]
                .split(",")
                .map { Role.valueOf(it.trim()) }
                .toSet()
        return UserPrincipal(
            id = Id.from(this[UserTable.id]),
            username = this[UserTable.username],
            roles = roles,
        )
    }

    companion object {
        private const val SESSION_TTL_SECONDS = 30L * 24 * 3600 // 30 days
        private val secureRandom = SecureRandom()
    }
}
