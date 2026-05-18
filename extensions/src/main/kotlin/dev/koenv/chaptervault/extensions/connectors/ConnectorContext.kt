package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse

interface ConnectorContext {
    val httpClient: HttpClient
    suspend fun get(url: String, params: Map<String, String> = emptyMap(), bucket: String = "api", headers: Map<String, String> = emptyMap()): Result<HttpResponse>
    suspend fun download(url: String, bucket: String = "cdn", headers: Map<String, String> = emptyMap()): Result<ByteArray>
}
