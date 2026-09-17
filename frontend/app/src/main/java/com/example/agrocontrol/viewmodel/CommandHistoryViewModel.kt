package com.example.agrocontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.agrocontrol.data.local.AppDatabase
import com.example.agrocontrol.data.local.CommandHistoryEntity
import com.example.agrocontrol.data.repository.CommandHistoryRepository
import kotlinx.coroutines.launch

class CommandHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CommandHistoryRepository

    val allCommands: LiveData<List<CommandHistoryEntity>>

    init {
        val commandHistoryDao = AppDatabase.getDatabase(application).commandHistoryDao()
        repository = CommandHistoryRepository(commandHistoryDao)
        allCommands = repository.allCommands.asLiveData()
    }

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
        return repository.logCommand(
            commandType,
            commandData,
            status,
            isUserAction,
            syncId,
            aiModelUsed,
            aiResponseRaw,
            aiResponseParsed,
            aiConfidence,
            aiProcessingTime,
            aiModelVersion
        )
    }

    fun updateCommandStatus(id: Long, newStatus: String, response: String? = null) = viewModelScope.launch {
        repository.updateCommandStatus(id, newStatus, response)
    }

    fun softDeleteCommand(id: Long, reason: String? = null) = viewModelScope.launch {
        repository.softDelete(id, reason)
    }

    fun restoreCommand(id: Long) = viewModelScope.launch {
        repository.restore(id)
    }

    fun deleteAllCommands() = viewModelScope.launch {
        repository.deleteAllCommands()
    }

    fun updateAiResponse(
        id: Long,
        model: String?,
        raw: String?,
        parsed: String?,
        confidence: Float?,
        time: Long?,
        version: String?
    ) = viewModelScope.launch {
        repository.updateAiResponse(id, model, raw, parsed, confidence, time, version)
    }

    fun updateServerResponse(
        id: Long,
        serverResponseRaw: String?,
        serverResponseType: String?,
        responseTimestamp: Long?,
        responseTimeMs: Long?,
        responseStatusCode: Int?,
        detailedDescription: String?
    ) = viewModelScope.launch {
        repository.updateServerResponse(
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
