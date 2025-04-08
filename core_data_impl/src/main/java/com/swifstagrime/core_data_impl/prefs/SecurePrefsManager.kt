package com.swifstagrime.core_data_impl.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.swifstagrime.core_common.constants.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurePrefsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val AUTH_PREFS_FILE_NAME = "secure_auth_prefs"
        const val KEY_PIN_HASH = "pin_hash"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, Constants.MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                AUTH_PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw RuntimeException("Could not create EncryptedSharedPreferences", e)
        }
    }

    fun getString(key: String, defaultValue: String?): String? {
        return encryptedPrefs.getString(key, defaultValue)
    }

    fun putString(key: String, value: String?) {
        encryptedPrefs.edit() { putString(key, value) }
    }

    fun remove(key: String) {
        encryptedPrefs.edit() { remove(key) }
    }

    fun contains(key: String): Boolean {
        return encryptedPrefs.contains(key)
    }
}