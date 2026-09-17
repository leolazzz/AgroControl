package com.example.agrocontrol.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SensorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sensorData: SensorDataEntity): Long

    @Query("SELECT * FROM sensor_data ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestData(): SensorDataEntity?
}
