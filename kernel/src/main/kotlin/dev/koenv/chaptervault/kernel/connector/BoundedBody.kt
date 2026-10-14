package dev.koenv.chaptervault.kernel.connector

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

private const val READ_CHUNK_BYTES = 8 * 1024

/**
 * Reads the body but never holds more than [maxBytes] of it. A declared Content-Length over the limit is refused
 * without reading anything; otherwise the count is enforced while streaming, because the header can be absent or false.
 *
 * @throws ResponseTooLargeException when the body exceeds [maxBytes]
 */
suspend fun HttpResponse.readBytesBounded(maxBytes: Int): ByteArray {
    contentLength()?.let { if (it > maxBytes) throw ResponseTooLargeException(maxBytes.toLong()) }
    val channel = bodyAsChannel()
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        val read = channel.readAvailable(chunk)
        if (read < 0) break
        if (out.size() + read > maxBytes) throw ResponseTooLargeException(maxBytes.toLong())
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
