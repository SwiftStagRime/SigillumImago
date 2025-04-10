package com.swifstagrime.feature_settings.data.repositories

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings.Global.putString
import com.swifstagrime.feature_settings.domain.models.AppTheme
import com.swifstagrime.feature_settings.domain.models.LockMethod
import com.swifstagrime.feature_settings.domain.repositories.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Use callbackFlow to listen for SharedPreferences changes reactively
    private val preferenceChangesFlow = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let { trySend(it).isSuccess } // Emit the key that changed
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Ensure initial values are emitted if needed by startingWith or similar downstream
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.shareIn( // Share the flow to avoid multiple listeners
        scope = CoroutineScope(Dispatchers.IO), // Use an appropriate scope
        started = SharingStarted.WhileSubscribed(),
        replay = 1 // Replay the last emission for new subscribers
    )

    // --- App Theme ---
    override val appTheme: Flow<AppTheme> = preferenceChangesFlow
        .filter { it == KEY_APP_THEME } // Trigger on specific key change or initial sub
        .map { getCurrentTheme() } // Map to current value
        .distinctUntilChanged() // Only emit if value actually changed
        .onStart { emit(getCurrentTheme()) } // Emit initial value

    override suspend fun setAppTheme(theme: AppTheme) {
        withContext(Dispatchers.IO) { // Perform disk write off main thread
            prefs.edit { putString(KEY_APP_THEME, theme.name) }
        }
    }

    private fun getCurrentTheme(): AppTheme {
        // Read from prefs, default to SYSTEM_DEFAULT
        return AppTheme.valueOf(prefs.getString(KEY_APP_THEME, AppTheme.SYSTEM_DEFAULT.name) ?: AppTheme.SYSTEM_DEFAULT.name)
    }

    // --- App Lock ---
    override val lockMethod: Flow<LockMethod> = preferenceChangesFlow
        .filter { it == KEY_LOCK_METHOD }
        .map { getCurrentLockMethod() }
        .distinctUntilChanged()
        .onStart { emit(getCurrentLockMethod()) }


    override val isAppLockEnabled: Flow<Boolean> = lockMethod.map { it != LockMethod.NONE }
        .distinctUntilChanged()


    override suspend fun setLockMethod(method: LockMethod) {
        withContext(Dispatchers.IO) {
            prefs.edit{putString(KEY_LOCK_METHOD, method.name)}
        }
    }

    private fun getCurrentLockMethod(): LockMethod {
        // Read from prefs, default to NONE
        return LockMethod.valueOf(prefs.getString(KEY_LOCK_METHOD, LockMethod.NONE.name) ?: LockMethod.NONE.name)
    }


    companion object {
        private const val PREFS_NAME = "secure_app_settings"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_LOCK_METHOD = "lock_method"
        // Add other keys as needed
    }
}