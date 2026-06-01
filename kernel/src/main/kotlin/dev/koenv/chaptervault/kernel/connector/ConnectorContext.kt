package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.statement.HttpResponse

interface ConnectorContext {
    suspend fun get(
        url: String,
        params: Map<String, String> = emptyMap(),
        bucket: BucketKey = Bucket.API,
        headers: Map<String, String> = emptyMap(),
    ): Result<HttpResponse>

    suspend fun download(
        url: String,
        bucket: BucketKey = Bucket.CDN,
        headers: Map<String, String> = emptyMap(),
    ): Result<ByteArray>
}
