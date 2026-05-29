package dev.koenv.chaptervault.shared.net

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CidrMatcherTest {

    @Test
    fun `matches IPv4 loopback in 127 0 0 0 slash 8`() {
        val m = CidrMatcher("127.0.0.0/8")
        assertTrue(m.matches("127.0.0.1"))
        assertTrue(m.matches("127.255.255.255"))
        assertFalse(m.matches("128.0.0.1"))
    }

    @Test
    fun `matches RFC1918 10 0 0 0 slash 8`() {
        val m = CidrMatcher("10.0.0.0/8")
        assertTrue(m.matches("10.0.0.1"))
        assertTrue(m.matches("10.255.255.255"))
        assertFalse(m.matches("11.0.0.0"))
    }

    @Test
    fun `matches RFC1918 192 168 0 0 slash 16`() {
        val m = CidrMatcher("192.168.0.0/16")
        assertTrue(m.matches("192.168.1.100"))
        assertFalse(m.matches("192.169.0.1"))
    }

    @Test
    fun `matches RFC1918 172 16 0 0 slash 12`() {
        val m = CidrMatcher("172.16.0.0/12")
        assertTrue(m.matches("172.16.0.1"))
        assertTrue(m.matches("172.31.255.255"))
        assertFalse(m.matches("172.32.0.0"))
    }

    @Test
    fun `matches IPv6 loopback exactly`() {
        val m = CidrMatcher("::1/128")
        assertTrue(m.matches("::1"))
        assertFalse(m.matches("::2"))
    }

    @Test
    fun `does not match IPv4 address against IPv6 CIDR`() {
        val m = CidrMatcher("::1/128")
        assertFalse(m.matches("127.0.0.1"))
    }

    @Test
    fun `does not match malformed IP`() {
        val m = CidrMatcher("10.0.0.0/8")
        assertFalse(m.matches("not-an-ip"))
        assertFalse(m.matches(""))
    }

    @Test
    fun `matches slash 32 exactly`() {
        val m = CidrMatcher("192.168.1.5/32")
        assertTrue(m.matches("192.168.1.5"))
        assertFalse(m.matches("192.168.1.6"))
    }

    @Test
    fun `matches slash 0 any address`() {
        val m = CidrMatcher("0.0.0.0/0")
        assertTrue(m.matches("1.2.3.4"))
        assertTrue(m.matches("255.255.255.255"))
    }
}
