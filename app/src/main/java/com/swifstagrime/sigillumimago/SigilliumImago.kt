package com.swifstagrime.sigillumimago

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.swifstagrime.core_common.model.AppTheme
import com.swifstagrime.core_data_api.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SigilliumImago : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        observeAndApplyTheme()
    }

    private fun observeAndApplyTheme() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            settingsRepository.appTheme
                .distinctUntilChanged()
                .collect { theme ->
                    applyTheme(theme)
                }
        }
    }

    private fun applyTheme(theme: AppTheme) {
        val mode = when (theme) {
            AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppTheme.SYSTEM_DEFAULT -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}