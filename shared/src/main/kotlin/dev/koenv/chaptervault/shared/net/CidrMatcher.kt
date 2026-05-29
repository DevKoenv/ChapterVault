package dev.koenv.chaptervault.shared.net

import java.net.InetAddress

class CidrMatcher(cidr: String) {
    private val networkBytes: ByteArray
    private val prefixLength: Int

    init {
        val parts = cidr.split("/")
        require(parts.size == 2) { "Invalid CIDR notation: $cidr" }
        networkBytes = InetAddress.getByName(parts[0]).address
        prefixLength = parts[1].toInt().also {
            require(it in 0..(networkBytes.size * 8)) { "Prefix length out of range: $cidr" }
        }
    }

    fun matches(ipAddress: String): Boolean {
        val addr = runCatching { InetAddress.getByName(ipAddress).address }.getOrNull() ?: return false
        if (addr.size != networkBytes.size) return false
        var remaining = prefixLength
        for (i in addr.indices) {
            if (remaining <= 0) break
            val bits = minOf(8, remaining)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((addr[i].toInt() and 0xFF and mask) != (networkBytes[i].toInt() and 0xFF and mask)) return false
            remaining -= 8
        }
        return true
    }
}
