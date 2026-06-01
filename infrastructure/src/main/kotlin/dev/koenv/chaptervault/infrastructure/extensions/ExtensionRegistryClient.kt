package dev.koenv.chaptervault.infrastructure.extensions

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ExtensionRegistryClient(
    private val httpClient: HttpClient,
) {
    private data class CacheEntry(
        val catalog: RegistryCatalog,
        val fetchedAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlSeconds = 600L
    private val log = LoggerFactory.getLogger(ExtensionRegistryClient::class.java)

    suspend fun fetch(url: String): RegistryCatalog {
        val cached = cache[url]
        if (cached != null && Instant.now().epochSecond - cached.fetchedAt.epochSecond < ttlSeconds) {
            return cached.catalog
        }
        val body = httpClient.get(url).bodyAsText()
        val catalog = parseJson(url, body)
        cache[url] = CacheEntry(catalog, Instant.now())
        return catalog
    }

    fun invalidate(url: String) {
        cache.remove(url)
    }

    private fun parseJson(
        url: String,
        body: String,
    ): RegistryCatalog {
        val root = Json.parseToJsonElement(body).jsonObject
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.int ?: 1
        val registryName = root["name"]?.jsonPrimitive?.content ?: url
        val extensions =
            root["extensions"]?.jsonArray?.mapNotNull { el ->
                try {
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content
                    val name = obj["name"]?.jsonPrimitive?.content
                    val version = obj["version"]?.jsonPrimitive?.content
                    val jarUrl = obj["jarUrl"]?.jsonPrimitive?.content
                    if (id == null || name == null || version == null || jarUrl == null) {
                        log.warn("Skipping extension entry from $url: missing required field (id/name/version/jarUrl)")
                        return@mapNotNull null
                    }
                    CatalogEntry(
                        id = id,
                        name = name,
                        version = version,
                        jarUrl = jarUrl,
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        author = obj["author"]?.jsonPrimitive?.content ?: "",
                        minServerVersion = obj["minServerVersion"]?.jsonPrimitive?.content ?: "1.0.0",
                    )
                } catch (e: Exception) {
                    log.warn("Skipping malformed extension entry from $url: ${e.message}")
                    null
                }
            } ?: emptyList()
        return RegistryCatalog(schemaVersion, registryName, extensions)
    }
}
