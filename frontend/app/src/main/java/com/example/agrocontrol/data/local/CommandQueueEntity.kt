package com.example.agrocontrol.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_queue")
data class CommandQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val command_type: String,
    val relay_id: Int? = null,
    val target_state: Boolean? = null,
    val parameter: String? = null,
    val timestamp: Long,
    val attempt_count: Int = 0,
    val last_attempt: Long? = null,
    val status: String = "PENDING"
)
