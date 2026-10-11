package com.samtex.eidreader.core.di

import android.content.Context
import androidx.room.Room
import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.data.history.HistoryDatabase
import com.samtex.eidreader.data.history.ScanHistoryDao
import com.samtex.eidreader.security.KeystoreSecureStorage
import com.samtex.eidreader.security.SecureStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.SYSTEM

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideHistoryDatabase(@ApplicationContext context: Context): HistoryDatabase =
        Room.databaseBuilder(context, HistoryDatabase::class.java, "samtex-history.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideScanHistoryDao(db: HistoryDatabase): ScanHistoryDao = db.scanHistoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    @Singleton
    abstract fun bindSecureStorage(impl: KeystoreSecureStorage): SecureStorage
}
