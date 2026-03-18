package dev.koenv.chaptervault.api.models.addon

import kotlinx.serialization.Serializable

@Serializable
data class AddonDto(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val state: String,
    val connectorIds: List<String>,
    val depends: List<String>,
    val optionalDepends: List<String>
)

@Serializable
data class AddonListResponse(val addons: List<AddonDto>)

@Serializable
data class AddonDetailResponse(
    val addon: AddonDto,
    val errors: List<AddonErrorDto>
)

@Serializable
data class AddonErrorDto(
    val phase: String,
    val message: String,
    val stackTrace: String?,
    val occurredAt: String
)

@Serializable
data class AddonActionResponse(val id: String, val state: String, val message: String)

@Serializable
data class AddonErrorListResponse(val errors: List<AddonErrorDto>)

@Serializable
data class AddonErrorsEntry(val addonId: String, val addonName: String, val errors: List<AddonErrorDto>)

@Serializable
data class AddonAllErrorsResponse(val addons: List<AddonErrorsEntry>)
