package dev.koenv.chaptervault.infrastructure.network

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OutboundUrlPolicyTest {
    private val hosts =
        mapOf(
            "ntfy.example.org" to listOf("93.184.216.34"),
            "gotify.lan" to listOf("192.168.1.20"),
            "docker-service" to listOf("172.18.0.5"),
            "rebind.example.org" to listOf("93.184.216.34", "127.0.0.1"),
            "metadata.example.org" to listOf("169.254.169.254"),
            "localhost" to listOf("127.0.0.1"),
        )
    private val ipLiteral = Regex("^[0-9.]+$|^[0-9a-fA-F:]+$")

    private fun policy(allowLocalTargets: Boolean = false) =
        OutboundUrlPolicy(allowLocalTargets) { host ->
            hosts[host]?.map { InetAddress.getByName(it) }
                ?: if (ipLiteral.matches(host)) InetAddress.getAllByName(host).toList() else throw UnknownHostException(host)
        }

    @Test
    fun `public https and http targets are allowed`() {
        assertNull(policy().violation("https://ntfy.example.org/topic"))
        assertNull(policy().violation("http://ntfy.example.org:8080/topic"))
    }

    @Test
    fun `private LAN and container addresses are allowed because that is where self-hosted targets live`() {
        assertNull(policy().violation("http://gotify.lan:8080"))
        assertNull(policy().violation("http://docker-service/message"))
        assertNull(policy().violation("http://192.168.1.50:2586/topic"))
        assertNull(policy().violation("http://10.0.0.7/hook"))
    }

    @Test
    fun `only http and https are allowed`() {
        listOf(
            "file:///etc/passwd",
            "ftp://ntfy.example.org/x",
            "gopher://ntfy.example.org",
            "jar:file:/x.jar!/",
            "ntfy.example.org",
        ).forEach {
            assertNotNull(policy().violation(it), it)
        }
    }

    @Test
    fun `loopback link-local and unspecified addresses are refused by default`() {
        listOf(
            "http://localhost/x",
            "http://127.0.0.1:8080/x",
            "http://[::1]/x",
            "http://169.254.169.254/latest/meta-data",
            "http://[fe80::1]/x",
            "http://0.0.0.0/x",
        ).forEach { assertNotNull(policy().violation(it), it) }
    }

    @Test
    fun `a hostname is judged by what it resolves to`() {
        assertNotNull(policy().violation("http://metadata.example.org/x"))
        assertNotNull(policy().violation("http://rebind.example.org/x"), "one bad address among several is enough")
    }

    @Test
    fun `local targets can be allowed explicitly but the scheme rule still applies`() {
        val allowing = policy(allowLocalTargets = true)

        assertNull(allowing.violation("http://localhost:2586/topic"))
        assertNull(allowing.violation("http://127.0.0.1/x"))
        assertNotNull(allowing.violation("file:///etc/passwd"))
    }

    @Test
    fun `a host that does not resolve is not refused here`() {
        assertNull(policy().violation("https://unknown.example.org/x"))
    }

    @Test
    fun `malformed urls are refused with a reason`() {
        assertEquals("URL must not be blank", policy().violation("  "))
        assertNotNull(policy().violation("http://"))
        assertNotNull(policy().violation("http://exa mple.org"))
    }
}
