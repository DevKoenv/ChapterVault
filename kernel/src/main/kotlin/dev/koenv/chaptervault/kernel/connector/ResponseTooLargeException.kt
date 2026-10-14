package dev.koenv.chaptervault.kernel.connector

class ResponseTooLargeException(
    val limit: Long,
) : RuntimeException("Response is larger than the allowed $limit bytes")
