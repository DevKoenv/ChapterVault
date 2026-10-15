package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.statement.HttpResponse

interface ConnectorContext {
    /**
     * [url] is either absolute (passed through unchanged) or relative, resolved against the
     * connector's own base URL.
     */
    suspend fun get(
        url: String,
        params: QueryParams = QueryParams.of(),
        bucket: BucketKey = Bucket.API,
        headers: Map<String, String> = emptyMap(),
    ): Result<HttpResponse>

    suspend fun download(
        url: String,
        bucket: BucketKey = Bucket.CDN,
        headers: Map<String, String> = emptyMap(),
    ): Result<ByteArray>
}
