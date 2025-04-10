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

    private val preferenceChangesFlow = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let { trySend(it).isSuccess }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.shareIn(
        scope = CoroutineScope(Dispatchers.IO),
        started = SharingStarted.WhileSubscribed(),
        replay = 1
    )

    override val appTheme: Flow<AppTheme> = preferenceChangesFlow
        .filter { it == KEY_APP_THEME }
        .map { getCurrentTheme() }
        .distinctUntilChanged()
        .onStart { emit(getCurrentTheme()) }

    override suspend fun setAppTheme(theme: AppTheme) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(KEY_APP_THEME, theme.name) }
        }
    }

    private fun getCurrentTheme(): AppTheme {
        return AppTheme.valueOf(prefs.getString(KEY_APP_THEME, AppTheme.SYSTEM_DEFAULT.name) ?: AppTheme.SYSTEM_DEFAULT.name)
    }

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
        return LockMethod.valueOf(prefs.getString(KEY_LOCK_METHOD, LockMethod.NONE.name) ?: LockMethod.NONE.name)
    }


    companion object {
        private const val PREFS_NAME = "secure_app_settings"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_LOCK_METHOD = "lock_method"
    }


}