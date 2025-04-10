package com.swifstagrime.feature_settings.ui.fragments.settings

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.AppTheme
import com.swifstagrime.core_common.model.LockMethod
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.AuthRepository
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.core_data_api.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ClearDataState {
    object Idle : ClearDataState
    object Clearing : ClearDataState
    object Success : ClearDataState
    data class Error(val message: String) : ClearDataState
}

sealed interface UiEvent {
    object NavigateToPinSetupOrChange : UiEvent
    data class ShowToast(val message: String) : UiEvent
    data class ShowErrorSnackbar(val message: String) : UiEvent
}

sealed interface StorageUsageState {
    object Calculating : StorageUsageState
    data class Calculated(val formattedSize: String) : StorageUsageState
    data class Error(val message: String) : StorageUsageState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val secureMediaRepository: SecureMediaRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val appTheme: StateFlow<AppTheme> = settingsRepository.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM_DEFAULT)

    val lockMethod: StateFlow<LockMethod> = settingsRepository.lockMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LockMethod.NONE)

    val isAppLockEnabled: StateFlow<Boolean> = settingsRepository.isAppLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPinSet: StateFlow<Boolean> = flow { emit(authRepository.isPinSet().getOrNull() == true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _biometricStatus = MutableStateFlow(BiometricSupportStatus.CHECKING)
    val biometricStatus: StateFlow<BiometricSupportStatus> = _biometricStatus.asStateFlow()

    private val _clearDataState = MutableStateFlow<ClearDataState>(ClearDataState.Idle)
    val clearDataState: StateFlow<ClearDataState> = _clearDataState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _storageUsageState =
        MutableStateFlow<StorageUsageState>(StorageUsageState.Calculating)
    val storageUsageState: StateFlow<StorageUsageState> = _storageUsageState.asStateFlow()

    init {
        checkBiometricSupport()
        fetchStorageUsage()
    }

    fun onSetPinClicked() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.NavigateToPinSetupOrChange)
        }
    }

    private fun checkBiometricSupport() {
        viewModelScope.launch {
            val biometricManager = BiometricManager.from(context)
            val canAuthenticate =
                biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

            _biometricStatus.value = when (canAuthenticate) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricSupportStatus.READY
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricSupportStatus.NOT_SUPPORTED
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricSupportStatus.TEMPORARILY_UNAVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricSupportStatus.NEEDS_ENROLLMENT
                else -> BiometricSupportStatus.UNKNOWN_ERROR
            }
            Log.d(Constants.APP_TAG, "Biometric Check Result: ${_biometricStatus.value}")
        }
    }

    enum class BiometricSupportStatus {
        CHECKING,
        READY,
        NOT_SUPPORTED,
        TEMPORARILY_UNAVAILABLE,
        NEEDS_ENROLLMENT,
        UNKNOWN_ERROR
    }

    fun onAppThemeChanged(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
        }
    }

    fun onLockMethodSelected(method: LockMethod) {
        viewModelScope.launch {
            if (method == LockMethod.BIOMETRIC && _biometricStatus.value != BiometricSupportStatus.READY) {
                _uiEvents.emit(UiEvent.ShowToast("Biometrics not available or not enrolled."))
                return@launch
            }
            settingsRepository.setLockMethod(method)
        }
    }

    fun onClearAllDataConfirmed() {
        viewModelScope.launch {
            _clearDataState.value = ClearDataState.Clearing
            val result = withContext(Dispatchers.IO) {
                secureMediaRepository.deleteAllMedia()
            }
            when (result) {
                is Result.Success -> {
                    Log.i(Constants.APP_TAG, "All secure data cleared successfully.")
                    _clearDataState.value = ClearDataState.Success
                    _uiEvents.emit(UiEvent.ShowToast("All secure data cleared."))
                }

                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Error clearing secure data", result.exception)
                    val errorMsg = "Failed to clear data: ${result.exception.message}"
                    _clearDataState.value = ClearDataState.Error(errorMsg)
                    _uiEvents.emit(UiEvent.ShowErrorSnackbar(errorMsg))
                }
            }
            delay(200)
            _clearDataState.value = ClearDataState.Idle
        }
    }

    fun fetchStorageUsage() {
        viewModelScope.launch {
            _storageUsageState.value = StorageUsageState.Calculating
            val result = secureMediaRepository.getTotalUsedStorageBytes()

            when (result) {
                is Result.Success -> {
                    val formatted = Formatter.formatShortFileSize(context, result.data)
                    _storageUsageState.value = StorageUsageState.Calculated(formatted)
                    Log.d(
                        Constants.APP_TAG,
                        "Storage usage calculated: ${result.data} bytes ($formatted)"
                    )
                }

                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Failed to calculate storage usage", result.exception)
                    val errorMsg = "Error calculating storage"
                    _storageUsageState.value = StorageUsageState.Error(errorMsg)
                }
            }
        }
    }


}