package com.swifstagrime.feature_recorder.di

import com.swifstagrime.feature_recorder.data.services.AudioPlayerHelperImpl
import com.swifstagrime.feature_recorder.data.services.AudioRecorderHelperImpl
import com.swifstagrime.feature_recorder.domain.services.AudioPlayerHelper
import com.swifstagrime.feature_recorder.domain.services.AudioRecorderHelper
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
abstract class RecorderModule {

    @Binds
    @ViewModelScoped
    abstract fun bindAudioRecorderHelper(
        impl: AudioRecorderHelperImpl
    ): AudioRecorderHelper

    @Binds
    @ViewModelScoped
    abstract fun bindAudioPlayerHelper(
        impl: AudioPlayerHelperImpl
    ): AudioPlayerHelper
}