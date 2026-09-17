package com.example.agrocontrol.model

data class ControlData(
    val mode: String? = null,
    val relay1: Boolean? = null,
    val relay2: Boolean? = null,
    val relay3: Boolean? = null,
    val ec_onoff: Boolean? = null,
    val ph_onoff: Boolean? = null
)

data class DiagnosticsData(
    val telemetry_source: String? = null,
    val telemetry_complete: Boolean = true,
    val light: String,
    val humidity: String,
    val air_temp: String,
    val solution_temp: String,
    val level: String,
    val ec: String,
    val ph: String,
    val mode: String? = null,
    val relay1: Boolean? = null,
    val relay2: Boolean? = null,
    val relay3: Boolean? = null,
    val ec_onoff: Boolean? = null,
    val ph_onoff: Boolean? = null
)

data class RecommendationSource(
    val id: String,
    val title: String,
    val organization: String,
    val url: String
)

data class RetrievalMetadata(
    val enabled: Boolean = false,
    val grounded: Boolean = false,
    val knowledge_version: String? = null,
    val matched_documents: Int = 0
)

data class RecResponse(
    val recommendations: String,
    val recommendation_source: String? = null,
    val language_validated: Boolean = false,
    val sources: List<RecommendationSource> = emptyList(),
    val retrieval: RetrievalMetadata? = null,
    val sensor_snapshot: Map<String, Any> = emptyMap(),
    val generation: GenerationMetadata? = null
)

data class GenerationMetadata(
    val llm_attempted: Boolean = false,
    val llm_used: Boolean = false,
    val model: String? = null,
    val mode: String? = null
)

data class UploadPhotoResponse(
    val disease_name: String,
    val confidence_score: Double,
    val metadata: UploadPhotoMetadata? = null
)

data class UploadPhotoMetadata(
    val class_index: Int? = null,
    val accepted: Boolean? = null,
    val rejection_reason: String? = null,
    val prototype_distance: Double? = null,
    val mode: String? = null,
    val sensors: Map<String, String>? = null
)

data class RecommendationsRequest(
    val type: String? = null,
    val disease_name: String? = null,
    val confidence_score: Double? = null,
    val sensor_data: Map<String, Any>? = null,
    val sensors: Map<String, String>? = null,
    val mode: String? = null,
    val plant: String? = null,
    val stage: String? = null,
    val include_sensors: Boolean = true
)

data class ApiResponse(
    val status: String? = null,
    val message: String? = null
)

data class LoginRequest(val username: String, val password: String)

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val confirm_password: String
)

data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val token_type: String,
    val expires_in: Int,
    val user: UserInfo
)

data class UserInfo(
    val id: Int,
    val username: String,
    val email: String,
    val role: Int
)

data class RefreshTokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
)

data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
    val confirm_password: String
)

data class CommandSyncRequest(val commands: List<CommandSyncItem>)

data class CommandSyncItem(
    val local_id: Long,
    val command_type: String,
    val relay_id: Int? = null,
    val target_state: Boolean? = null,
    val parameter: String? = null
)
