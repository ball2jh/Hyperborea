package com.nettarion.hyperborea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceConfigDao {
    @Query("SELECT * FROM device_configs WHERE modelNumber = :modelNumber")
    suspend fun getConfig(modelNumber: Int): DeviceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeviceConfigEntity)

    @Query("DELETE FROM device_configs WHERE modelNumber = :modelNumber")
    suspend fun delete(modelNumber: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM device_configs WHERE modelNumber = :modelNumber)")
    suspend fun exists(modelNumber: Int): Boolean
}
