package com.example.agrocontrol.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_data")
data class SensorDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val temperature: Float,
    val humidity: Float,
    val ph: Float,
    val ec: Float,
    val light: Int,
    val water_level: Float,
    val mode: String,
    val is_synced: Boolean = false
)
