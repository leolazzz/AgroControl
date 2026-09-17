package com.example.agrocontrol.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface CommandQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CommandQueueEntity): Long

    @Update
    suspend fun update(command: CommandQueueEntity)

    @Delete
    suspend fun delete(command: CommandQueueEntity)

    @Query("SELECT * FROM command_queue WHERE status = 'PENDING' OR (status = 'FAILED' AND attempt_count < 5) ORDER BY timestamp ASC")
    suspend fun getPendingCommands(): List<CommandQueueEntity>

    @Query("DELETE FROM command_queue WHERE status IN ('PENDING', 'FAILED') AND command_type = :commandType AND ifnull(relay_id, -1) = ifnull(:relayId, -1)")
    suspend fun deleteSuperseded(commandType: String, relayId: Int?)

    @Transaction
    suspend fun replacePending(command: CommandQueueEntity): Long {
        deleteSuperseded(command.command_type, command.relay_id)
        return insert(command)
    }

    @Query("SELECT * FROM command_queue WHERE id = :id")
    suspend fun getCommandById(id: Long): CommandQueueEntity?

    @Query("DELETE FROM command_queue WHERE status = 'SUCCESS' OR (status = 'FAILED' AND attempt_count >= :maxAttempts)")
    suspend fun pruneCommands(maxAttempts: Int = 5)
}
