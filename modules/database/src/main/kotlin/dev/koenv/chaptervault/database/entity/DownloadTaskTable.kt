package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Table for persisting download task progress
 * Allows recovery after restarts
 */
object DownloadTaskTable : UUIDTable("download_tasks") {
    val taskType = varchar("task_type", 32)
    val targetUrl = varchar("target_url", 512)
    val seriesId = reference("series_id", SeriesTable).nullable()
    val chapterId = reference("chapter_id", ChapterTable).nullable()

    val status = varchar("status", 32)
    val message = text("message").nullable()
    val currentProgress = integer("current_progress").default(0)
    val totalProgress = integer("total_progress").default(0)
    val errorMessage = text("error_message").nullable()

    val createdAt = timestamp("created_at")
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
}
