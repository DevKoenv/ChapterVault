package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import io.ktor.server.auth.Principal

data class KtorPrincipal(val user: UserPrincipal) : Principal
