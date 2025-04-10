package com.swifstagrime.feature_settings.domain.repositories

import com.swifstagrime.feature_settings.domain.models.AppTheme
import com.swifstagrime.feature_settings.domain.models.LockMethod
import kotlinx.coroutines.flow.Flow


interface SettingsRepository {

    val appTheme: Flow<AppTheme>
    suspend fun setAppTheme(theme: AppTheme)

    val isAppLockEnabled: Flow<Boolean>
    val lockMethod: Flow<LockMethod>
    suspend fun setLockMethod(method: LockMethod)

}