package com.nettarion.hyperborea.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, RideSummaryEntity::class, WorkoutSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HyperboreaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN savedSensorAddress TEXT DEFAULT NULL")
            }
        }
    }
}
