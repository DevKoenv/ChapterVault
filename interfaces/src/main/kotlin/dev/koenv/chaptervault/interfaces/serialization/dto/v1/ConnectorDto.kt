package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class ConnectorDto(
    val id: String,
    val name: String,
)
