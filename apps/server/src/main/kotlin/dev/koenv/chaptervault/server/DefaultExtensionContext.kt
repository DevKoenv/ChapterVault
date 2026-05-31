package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionConfig
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DefaultExtensionContext(
    override val httpClient: HttpClient,
    override val library: LibraryReadApi,
    override val progress: ProgressApi,
    override val system: SystemApi,
    override val connectorRegistry: ConnectorRegistry,
    override val enricherRegistry: MetadataEnricherRegistry,
    override val notificationRegistry: NotificationChannelRegistry,
    override val config: ExtensionConfig,
    override val dataDir: Path,
) : ExtensionContext {
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()

    override fun rateLimiter(
        bucket: String,
        requestsPerSecond: Double,
    ): RateLimiter = rateLimiters.computeIfAbsent(bucket) { RateLimiter(requestsPerSecond) }

    override fun logger(name: String): Logger = LoggerFactory.getLogger(name)
}
