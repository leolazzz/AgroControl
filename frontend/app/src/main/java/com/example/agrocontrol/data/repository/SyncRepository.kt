package com.example.agrocontrol.data.repository

import android.util.Log
import com.example.agrocontrol.data.local.CommandQueueDao
import com.example.agrocontrol.model.CommandSyncItem
import com.example.agrocontrol.model.CommandSyncRequest
import com.example.agrocontrol.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncRepository(
    private val apiService: ApiService,
    private val commandQueueDao: CommandQueueDao
) {

    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        val commandSuccess = syncCommands()
        commandQueueDao.pruneCommands()
        commandSuccess
    }

    suspend fun syncCommands(): Boolean {
        val pendingCommands = commandQueueDao.getPendingCommands()
        if (pendingCommands.isEmpty()) return true

        val commandItems = pendingCommands.map {
            CommandSyncItem(
                local_id = it.id,
                command_type = it.command_type,
                relay_id = it.relay_id,
                target_state = it.target_state,
                parameter = it.parameter
            )
        }

        try {
            val response = apiService.syncCommands(CommandSyncRequest(commandItems))

            if (response.isSuccessful) {
                val body = response.body()
                val results = body?.get("results") as? List<*>

                results?.forEach { rawResult ->
                    val result = rawResult as? Map<*, *> ?: return@forEach
                    val localId = (result["local_id"] as? Number)?.toLong()
                    val status = result["status"] as? String

                    if (localId != null) {
                        val command = commandQueueDao.getCommandById(localId)
                        if (command != null) {
                            if (status == "success") {
                                commandQueueDao.update(command.copy(status = "SUCCESS"))
                            } else {
                                commandQueueDao.update(
                                    command.copy(
                                        attempt_count = command.attempt_count + 1,
                                        status = if (command.attempt_count + 1 >= 5) "FAILED" else "PENDING"
                                    )
                                )
                            }
                        }
                    }
                }
                return results?.all { rawResult ->
                    (rawResult as? Map<*, *>)?.get("status") == "success"
                } ?: false
            }
            Log.e("SyncRepository", "Command sync failed: HTTP ${response.code()}")
            return false
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error syncing commands", e)
            pendingCommands.forEach { cmd ->
                commandQueueDao.update(
                    cmd.copy(
                        attempt_count = cmd.attempt_count + 1
                    )
                )
            }
            return false
        }
    }
}
