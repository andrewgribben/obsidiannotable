package com.ethran.notable.di

import com.ethran.notable.io.obsidiansync.ObsidianSyncOrchestrator
import com.ethran.notable.io.obsidiansync.ObsidianSyncProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ObsidianSyncModule {

    @Provides
    @Singleton
    fun provideObsidianSyncOrchestrator(): ObsidianSyncOrchestrator = ObsidianSyncOrchestrator()

    @Provides
    @Singleton
    fun provideObsidianSyncProbe(): ObsidianSyncProbe = ObsidianSyncProbe()
}
