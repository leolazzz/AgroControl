package com.example.agrocontrol.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CommandHistoryEntity): Long

    @Query("SELECT * FROM command_history WHERE is_deleted = 0 ORDER BY timestamp DESC")
    fun getAll(): Flow<List<CommandHistoryEntity>>

    @Query("DELETE FROM command_history")
    suspend fun deleteAll()

    @Query("UPDATE command_history SET status = :newStatus, serverResponse = :response, lastAttemptTimestamp = :timestamp WHERE id = :id")
    suspend fun updateStatusAndResponse(
        id: Long,
        newStatus: String,
        response: String? = null,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE command_history SET is_deleted = 1, deleted_at = :timestamp, delete_reason = :reason WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis(), reason: String? = null)

    @Query("UPDATE command_history SET is_deleted = 0, deleted_at = NULL, delete_reason = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE command_history SET ai_model_used = :model, ai_response_raw = :raw, ai_response_parsed = :parsed, ai_confidence = :confidence, ai_processing_time = :time, ai_model_version = :version WHERE id = :id")
    suspend fun updateAiResponse(
        id: Long,
        model: String?,
        raw: String?,
        parsed: String?,
        confidence: Float?,
        time: Long?,
        version: String?
    )

    @Query("UPDATE command_history SET server_response_raw = :serverResponseRaw, server_response_type = :serverResponseType, response_timestamp = :responseTimestamp, response_time_ms = :responseTimeMs, response_status_code = :responseStatusCode, detailed_description = :detailedDescription WHERE id = :id")
    suspend fun updateServerResponse(
        id: Long,
        serverResponseRaw: String?,
        serverResponseType: String?,
        responseTimestamp: Long?,
        responseTimeMs: Long?,
        responseStatusCode: Int?,
        detailedDescription: String?
    )
}
