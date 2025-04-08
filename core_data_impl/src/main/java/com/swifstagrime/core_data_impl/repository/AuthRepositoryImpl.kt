package com.swifstagrime.core_data_impl.repository

import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_common.utils.wrapResultSync
import com.swifstagrime.core_data_api.repository.AuthRepository
import com.swifstagrime.core_data_impl.prefs.SecurePrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val securePrefsManager: SecurePrefsManager
) : AuthRepository {

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.fold("") { str, it -> str + "%02x".format(it) }
    }

    override suspend fun setPin(pin: String): Result<Unit> = withContext(Dispatchers.IO) {
        wrapResultSync {
            if (pin.length < 5) throw IllegalArgumentException("PIN must be at least 5 digits")
            val hashedPin = hashPin(pin)
            securePrefsManager.putString(SecurePrefsManager.KEY_PIN_HASH, hashedPin)
        }
    }

    override suspend fun verifyPin(enteredPin: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            wrapResultSync {
                val storedHash = securePrefsManager.getString(SecurePrefsManager.KEY_PIN_HASH, null)
                if (storedHash == null) {
                    return@wrapResultSync false
                }
                val enteredHash = hashPin(enteredPin)
                val matches = storedHash == enteredHash
                matches
            }
        }

    override suspend fun isPinSet(): Result<Boolean> = withContext(Dispatchers.IO) {
        wrapResultSync {
            securePrefsManager.contains(SecurePrefsManager.KEY_PIN_HASH)
        }
    }

    override suspend fun clearPin(): Result<Unit> = withContext(Dispatchers.IO) {
        wrapResultSync {
            securePrefsManager.remove(SecurePrefsManager.KEY_PIN_HASH)
        }
    }
}

