package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Table for persisting task progress.
 * Uses target_type + target_id (stored as string, no FK) instead of FK columns
 * to decouple tasks from domain entities.
 */
object TaskTable : UUIDTable("tasks") {
    val type = varchar("type", 32)
    val targetUrl = varchar("target_url", 512)
    val targetType = varchar("target_type", 32)
    val targetId = varchar("target_id", 36).nullable()   // UUID as string, no FK
    val status = varchar("status", 32)
    val message = text("message").nullable()
    val currentProgress = integer("current_progress").default(0)
    val totalProgress = integer("total_progress").default(0)
    val errorMessage = text("error_message").nullable()
    val createdAt = timestamp("created_at")
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
}
