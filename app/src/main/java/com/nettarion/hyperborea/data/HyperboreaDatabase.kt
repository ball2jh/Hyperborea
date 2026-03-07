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
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN avgResistance INTEGER")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN maxResistance INTEGER")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN avgIncline REAL")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN maxIncline REAL")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN totalElevationGainMeters REAL")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN normalizedPower INTEGER")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN intensityFactor REAL")
                db.execSQL("ALTER TABLE ride_summaries ADD COLUMN trainingStressScore REAL")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS workout_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rideId INTEGER NOT NULL,
                        timestampSeconds INTEGER NOT NULL,
                        power INTEGER,
                        cadence INTEGER,
                        speedKph REAL,
                        heartRate INTEGER,
                        resistance INTEGER,
                        incline REAL,
                        calories INTEGER,
                        distanceKm REAL,
                        FOREIGN KEY(rideId) REFERENCES ride_summaries(id) ON DELETE CASCADE
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_samples_rideId ON workout_samples(rideId)")
            }
        }
    }
}
