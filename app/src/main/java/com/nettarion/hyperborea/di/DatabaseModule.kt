package com.nettarion.hyperborea.di

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.data.HyperboreaDatabase
import com.nettarion.hyperborea.data.ProfileDao
import com.nettarion.hyperborea.data.RoomProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): HyperboreaDatabase = androidx.room.Room.databaseBuilder(
        context,
        HyperboreaDatabase::class.java,
        "hyperborea.db",
    ).addMigrations(HyperboreaDatabase.MIGRATION_1_2).build()

    @Provides
    @Singleton
    fun provideProfileDao(database: HyperboreaDatabase): ProfileDao = database.profileDao()

    @Provides
    @Singleton
    fun provideProfileRepository(
        database: HyperboreaDatabase,
        dao: ProfileDao,
        logger: AppLogger,
        scope: CoroutineScope,
    ): ProfileRepository = RoomProfileRepository(database, dao, logger, scope)
}
