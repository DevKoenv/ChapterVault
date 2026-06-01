package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ExtensionConfigTable
import dev.koenv.chaptervault.kernel.extension.ExtensionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert

class ExtensionConfigRepository {
    suspend fun get(
        extensionId: String,
        key: String,
    ): String? =
        newSuspendedTransaction(Dispatchers.IO) {
            ExtensionConfigTable
                .selectAll()
                .where {
                    (ExtensionConfigTable.extensionId eq extensionId) and (ExtensionConfigTable.key eq key)
                }.singleOrNull()
                ?.get(ExtensionConfigTable.value)
        }

    suspend fun getAll(extensionId: String): Map<String, String> =
        newSuspendedTransaction(Dispatchers.IO) {
            ExtensionConfigTable
                .selectAll()
                .where { ExtensionConfigTable.extensionId eq extensionId }
                .associate { it[ExtensionConfigTable.key] to it[ExtensionConfigTable.value] }
        }

    suspend fun set(
        extensionId: String,
        key: String,
        value: String,
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            ExtensionConfigTable.upsert {
                it[ExtensionConfigTable.extensionId] = extensionId
                it[ExtensionConfigTable.key] = key
                it[ExtensionConfigTable.value] = value
            }
        }
    }

    suspend fun setAll(
        extensionId: String,
        values: Map<String, String>,
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            values.forEach { (key, value) ->
                ExtensionConfigTable.upsert {
                    it[ExtensionConfigTable.extensionId] = extensionId
                    it[ExtensionConfigTable.key] = key
                    it[ExtensionConfigTable.value] = value
                }
            }
        }
    }

    fun forExtension(extensionId: String): ExtensionConfig =
        object : ExtensionConfig {
            override fun get(key: String): String? =
                // ExtensionConfig.get is synchronous (called from onEnable which runs on a plain
                // thread, never from a coroutine dispatcher). runBlocking is safe here.
                runBlocking { this@ExtensionConfigRepository.get(extensionId, key) }
        }
}
