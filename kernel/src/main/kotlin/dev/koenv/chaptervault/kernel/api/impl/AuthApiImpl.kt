package dev.koenv.chaptervault.kernel.api.impl

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result

class AuthApiImpl : AuthApi {
    override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> =
        Result.Failure(AppError.Unauthorized("Auth not implemented"))

    override suspend fun validateSession(token: String): Result<UserPrincipal> =
        Result.Failure(AppError.Unauthorized("Auth not implemented"))

    override suspend fun invalidateSession(token: String): Result<Unit> =
        Result.Success(Unit)
}
