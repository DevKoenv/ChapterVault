package dev.koenv.chaptervault.kernel.auth

import dev.koenv.chaptervault.shared.utils.Id
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPrincipalTest {
    private fun principal(vararg roles: Role) =
        UserPrincipal(
            id = Id.generate(),
            username = "test",
            roles = roles.toSet(),
        )

    @Test
    fun `hasRole returns true when role is in set`() {
        assertTrue(principal(Role.USER).hasRole(Role.USER))
    }

    @Test
    fun `hasRole returns false when role is not in set`() {
        assertFalse(principal(Role.GUEST).hasRole(Role.ADMIN))
    }

    @Test
    fun `hasPermission returns true when any role contains the permission`() {
        assertTrue(principal(Role.USER).hasPermission(Permission.READ_LIBRARY))
    }

    @Test
    fun `hasPermission returns false when no role contains the permission`() {
        assertFalse(principal(Role.GUEST).hasPermission(Permission.WRITE_LIBRARY))
    }
}
