package com.nettarion.hyperborea.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, RideSummaryEntity::class, WorkoutSampleEntity::class, DeviceConfigEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HyperboreaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun deviceConfigDao(): DeviceConfigDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS device_configs (
                        modelNumber INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        supportedMetrics TEXT NOT NULL,
                        maxResistance INTEGER NOT NULL,
                        minResistance INTEGER NOT NULL,
                        minIncline REAL NOT NULL,
                        maxIncline REAL NOT NULL,
                        maxPower INTEGER NOT NULL,
                        minPower INTEGER NOT NULL,
                        powerStep INTEGER NOT NULL,
                        resistanceStep REAL NOT NULL,
                        inclineStep REAL NOT NULL,
                        speedStep REAL NOT NULL,
                        maxSpeed REAL NOT NULL
                    )"""
                )
            }
        }
    }
}
