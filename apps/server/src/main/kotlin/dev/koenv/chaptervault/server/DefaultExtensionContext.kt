package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.extension.ConnectorRegistrar
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

data class DefaultExtensionContext(
    override val httpClient: HttpClient,
    override val library: LibraryReadApi,
    override val progress: ProgressApi,
    override val system: SystemApi,
    override val connectorRegistry: ConnectorRegistrar,
    override val dataDir: Path,
) : ExtensionContext {
    override fun rateLimiter(
        bucket: String,
        requestsPerSecond: Double,
    ): RateLimiter = RateLimiter(requestsPerSecond)

    override fun logger(name: String): Logger = LoggerFactory.getLogger(name)
}
