package com.swifstagrime.core_common.utils

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable) : Result<Nothing>()

    val isSuccess get() = this is Success<*>
    val isError get() = this is Error

    fun getOrNull(): T? = (this as? Success<T>)?.data
    fun exceptionOrNull(): Throwable? = (this as? Error)?.exception

    @OptIn(ExperimentalContracts::class)
    inline fun <R> fold(
        onSuccess: (value: T) -> R,
        onFailure: (exception: Throwable) -> R
    ): R {
        contract {
            callsInPlace(onSuccess, InvocationKind.AT_MOST_ONCE)
            callsInPlace(onFailure, InvocationKind.AT_MOST_ONCE)
        }
        return when (this) {
            is Success -> onSuccess(data)
            is Error -> onFailure(exception)
        }
    }
}

suspend fun <T> wrapResult(block: suspend () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e)
    }
}

inline fun <T> wrapResultSync(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e)
    }
}