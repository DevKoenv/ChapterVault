package dev.koenv.chaptervault.infrastructure.extensions

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            root["extensions"]?.jsonArray?.map { el ->
                val obj = el.jsonObject
                CatalogEntry(
                    id = obj["id"]!!.jsonPrimitive.content,
                    name = obj["name"]!!.jsonPrimitive.content,
                    version = obj["version"]!!.jsonPrimitive.content,
                    jarUrl = obj["jarUrl"]!!.jsonPrimitive.content,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    author = obj["author"]?.jsonPrimitive?.content ?: "",
                    minServerVersion = obj["minServerVersion"]?.jsonPrimitive?.content ?: "1.0.0",
                )
            } ?: emptyList()
        return RegistryCatalog(schemaVersion, registryName, extensions)
    }
}
