package com.example.agrocontrol.data.repository

import com.example.agrocontrol.data.local.CommandHistoryDao
import com.example.agrocontrol.data.local.CommandHistoryEntity
import kotlinx.coroutines.flow.Flow

class CommandHistoryRepository(private val commandHistoryDao: CommandHistoryDao) {

    val allCommands: Flow<List<CommandHistoryEntity>> = commandHistoryDao.getAll()

    suspend fun logCommand(
        commandType: String,
        commandData: String,
        status: String = "PENDING",
        isUserAction: Boolean = true,
        syncId: String? = null,
        aiModelUsed: String? = null,
        aiResponseRaw: String? = null,
        aiResponseParsed: String? = null,
        aiConfidence: Float? = null,
        aiProcessingTime: Long? = null,
        aiModelVersion: String? = null
    ): Long {
        val command = CommandHistoryEntity(
            timestamp = System.currentTimeMillis(),
            commandType = commandType,
            commandData = commandData,
            status = status,
            isUserAction = isUserAction,
            syncId = syncId,
            aiModelUsed = aiModelUsed,
            aiResponseRaw = aiResponseRaw,
            aiResponseParsed = aiResponseParsed,
            aiConfidence = aiConfidence,
            aiProcessingTime = aiProcessingTime,
            aiModelVersion = aiModelVersion
        )
        return commandHistoryDao.insert(command)
    }

    suspend fun updateCommandStatus(id: Long, newStatus: String, response: String? = null) {
        commandHistoryDao.updateStatusAndResponse(id, newStatus, response)
    }

    suspend fun softDelete(id: Long, reason: String? = null) {
        commandHistoryDao.softDelete(id, System.currentTimeMillis(), reason)
    }

    suspend fun restore(id: Long) {
        commandHistoryDao.restore(id)
    }

    suspend fun deleteAllCommands() {
        commandHistoryDao.deleteAll()
    }

    suspend fun updateAiResponse(
        id: Long,
        model: String?,
        raw: String?,
        parsed: String?,
        confidence: Float?,
        time: Long?,
        version: String?
    ) {
        commandHistoryDao.updateAiResponse(id, model, raw, parsed, confidence, time, version)
    }

    suspend fun updateServerResponse(
        id: Long,
        serverResponseRaw: String?,
        serverResponseType: String?,
        responseTimestamp: Long?,
        responseTimeMs: Long?,
        responseStatusCode: Int?,
        detailedDescription: String?
    ) {
        commandHistoryDao.updateServerResponse(
            id,
            serverResponseRaw,
            serverResponseType,
            responseTimestamp,
            responseTimeMs,
            responseStatusCode,
            detailedDescription
        )
    }

}
