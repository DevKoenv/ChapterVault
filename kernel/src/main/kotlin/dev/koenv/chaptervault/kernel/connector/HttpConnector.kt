package dev.koenv.chaptervault.kernel.connector

import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.client.HttpClient

abstract class HttpConnector(
    private val httpClient: HttpClient,
) : Connector {
    /** Base URL that relative endpoints passed to [context] are resolved against. Absolute URLs bypass it. */
    protected open val baseUrl: String? = null

    abstract val bucketConfigs: Map<BucketKey, BucketConfig>

    private val buckets by lazy {
        bucketConfigs.mapValues { (_, cfg) -> RateLimiter(cfg.requestsPerSecond, cfg.burst) }
    }

    protected val context: ConnectorContext by lazy {
        DefaultConnectorContext(httpClient, buckets, baseUrl = baseUrl)
    }

    override suspend fun fetchPage(page: DownloadPage): Result<ByteArray> =
        context.download(page.url, bucket = Bucket.CDN, headers = page.headers)
}
