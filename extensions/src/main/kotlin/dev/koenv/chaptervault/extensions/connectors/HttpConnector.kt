package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient

abstract class HttpConnector(private val httpClient: HttpClient) : Connector {

    abstract val bucketConfigs: Map<BucketKey, BucketConfig>

    private val buckets by lazy {
        bucketConfigs.mapValues { (_, cfg) -> RateLimiter(cfg.requestsPerSecond, cfg.burst) }
    }

    protected val context: ConnectorContext by lazy {
        DefaultConnectorContext(httpClient, buckets)
    }

    open suspend fun fetchPage(page: DownloadPage): Result<ByteArray> =
        context.download(page.url, bucket = Bucket.CDN, headers = page.headers)
}
