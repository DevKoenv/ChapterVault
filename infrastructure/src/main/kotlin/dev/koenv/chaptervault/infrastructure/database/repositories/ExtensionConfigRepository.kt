package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ExtensionConfigTable
import dev.koenv.chaptervault.kernel.extension.ExtensionConfig
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

class ExtensionConfigRepository {
    fun get(
        extensionId: String,
        key: String,
    ): String? =
        transaction {
            ExtensionConfigTable
                .selectAll()
                .where {
                    (ExtensionConfigTable.extensionId eq extensionId) and (ExtensionConfigTable.key eq key)
                }.singleOrNull()
                ?.get(ExtensionConfigTable.value)
        }

    fun getAll(extensionId: String): Map<String, String> =
        transaction {
            ExtensionConfigTable
                .selectAll()
                .where { ExtensionConfigTable.extensionId eq extensionId }
                .associate { it[ExtensionConfigTable.key] to it[ExtensionConfigTable.value] }
        }

    fun set(
        extensionId: String,
        key: String,
        value: String,
    ) {
        transaction {
            ExtensionConfigTable.upsert {
                it[ExtensionConfigTable.extensionId] = extensionId
                it[ExtensionConfigTable.key] = key
                it[ExtensionConfigTable.value] = value
            }
        }
    }

    fun setAll(
        extensionId: String,
        values: Map<String, String>,
    ) {
        transaction {
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
            override fun get(key: String): String? = this@ExtensionConfigRepository.get(extensionId, key)
        }
}
