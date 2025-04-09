package com.swifstagrime.feature_recordings.di

import com.swifstagrime.feature_recordings.data.services.RecordingPlaybackServiceImpl
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
abstract class PlaybackServiceModule {

    @Binds
    @ViewModelScoped
    abstract fun bindRecordingPlaybackService(
        impl: RecordingPlaybackServiceImpl
    ): RecordingPlaybackService
}