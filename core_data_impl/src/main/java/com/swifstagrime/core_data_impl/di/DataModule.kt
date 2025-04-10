package com.swifstagrime.core_data_impl.di

import com.swifstagrime.core_data_api.repository.AuthRepository
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.core_data_api.repository.SettingsRepository
import com.swifstagrime.core_data_impl.repository.AuthRepositoryImpl
import com.swifstagrime.core_data_impl.repository.SecureMediaRepositoryImpl
import com.swifstagrime.core_data_impl.repository.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindSecureMediaRepository(
        impl: SecureMediaRepositoryImpl
    ): SecureMediaRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        impl: AuthRepositoryImpl
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository
}