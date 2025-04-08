package com.swifstagrime.core_data_api.repository

import com.swifstagrime.core_common.utils.Result

interface AuthRepository {
    suspend fun setPin(pin: String): Result<Unit>
    suspend fun verifyPin(enteredPin: String): Result<Boolean>
    suspend fun isPinSet(): Result<Boolean>
    suspend fun clearPin(): Result<Unit>
}