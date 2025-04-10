package com.swifstagrime.core_data_api.repository

import com.swifstagrime.core_common.model.AppTheme
import com.swifstagrime.core_common.model.LockMethod
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    val appTheme: Flow<AppTheme>
    suspend fun setAppTheme(theme: AppTheme)

    val isAppLockEnabled: Flow<Boolean>
    val lockMethod: Flow<LockMethod>
    suspend fun setLockMethod(method: LockMethod)

}