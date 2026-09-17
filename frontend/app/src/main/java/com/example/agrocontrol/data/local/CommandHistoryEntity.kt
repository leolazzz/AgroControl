package com.example.agrocontrol.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "command_history",
    indices = [
        Index(value = ["timestamp"], name = "idx_timestamp"),
        Index(value = ["timestamp", "status"], name = "idx_timestamp_status"),
        Index(value = ["timestamp", "commandType"], name = "idx_timestamp_type"),
        Index(value = ["timestamp", "is_deleted"], name = "idx_timestamp_deleted"),
        Index(value = ["is_deleted"], name = "idx_deleted")
    ]
)
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val commandType: String,
    val commandData: String,
    val status: String = "PENDING",
    val serverResponse: String? = null,
    val retryCount: Int = 0,
    val lastAttemptTimestamp: Long? = null,
    val isUserAction: Boolean = true,
    val syncId: String? = null,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "delete_reason")
    val deleteReason: String? = null,

    @ColumnInfo(name = "ai_model_used")
    val aiModelUsed: String? = null,

    @ColumnInfo(name = "ai_response_raw")
    val aiResponseRaw: String? = null,

    @ColumnInfo(name = "ai_response_parsed")
    val aiResponseParsed: String? = null,

    @ColumnInfo(name = "ai_confidence")
    val aiConfidence: Float? = null,

    @ColumnInfo(name = "ai_processing_time")
    val aiProcessingTime: Long? = null,

    @ColumnInfo(name = "ai_model_version")
    val aiModelVersion: String? = null,

    @ColumnInfo(name = "server_response_raw")
    val serverResponseRaw: String? = null,

    @ColumnInfo(name = "server_response_type")
    val serverResponseType: String? = null,

    @ColumnInfo(name = "response_timestamp")
    val responseTimestamp: Long? = null,

    @ColumnInfo(name = "response_time_ms")
    val responseTimeMs: Long? = null,

    @ColumnInfo(name = "response_status_code")
    val responseStatusCode: Int? = null,

    @ColumnInfo(name = "detailed_description")
    val detailedDescription: String? = null,

    @ColumnInfo(name = "location")
    val location: String? = null,

    @ColumnInfo(name = "priority")
    val priority: Int? = null
)
