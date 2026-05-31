package dev.koenv.chaptervault.kernel.extension

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import org.slf4j.Logger
import java.nio.file.Path

interface ExtensionContext {
    val httpClient: HttpClient
    val library: LibraryReadApi
    val progress: ProgressApi
    val system: SystemApi
    val connectorRegistry: ConnectorRegistry
    val dataDir: Path
    val enricherRegistry: MetadataEnricherRegistry
    val notificationRegistry: NotificationChannelRegistry
    val config: ExtensionConfig

    fun rateLimiter(
        bucket: String,
        requestsPerSecond: Double,
    ): RateLimiter

    fun logger(name: String): Logger
}
