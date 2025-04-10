package com.swifstagrime.feature_settings.ui.fragments.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.swifstagrime.core_common.utils.Result
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_data_api.repository.AuthRepository
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.feature_settings.domain.models.AppTheme
import com.swifstagrime.feature_settings.domain.models.LockMethod
import com.swifstagrime.feature_settings.domain.repositories.SettingsRepository
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
    object Success : ClearDataState // Transient state, UI shows snackbar
    data class Error(val message: String) : ClearDataState // Transient state, UI shows snackbar
}

// Event for Fragment actions that don't involve holding state
sealed interface UiEvent {
    object NavigateToPinSetupOrChange : UiEvent
    data class ShowToast(val message: String) : UiEvent // For simple feedback
    data class ShowErrorSnackbar(val message: String) : UiEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val secureMediaRepository: SecureMediaRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    // --- State Flows ---

    // Expose settings flows directly from the repository
    val appTheme: StateFlow<AppTheme> = settingsRepository.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM_DEFAULT)

    val lockMethod: StateFlow<LockMethod> = settingsRepository.lockMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LockMethod.NONE)

    val isAppLockEnabled: StateFlow<Boolean> = settingsRepository.isAppLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPinSet: StateFlow<Boolean> = flow { emit(authRepository.isPinSet().getOrNull() == true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    // State derived from BiometricManager
    private val _biometricStatus = MutableStateFlow(BiometricSupportStatus.CHECKING)
    val biometricStatus: StateFlow<BiometricSupportStatus> = _biometricStatus.asStateFlow()

    // State for the clear data operation
    private val _clearDataState = MutableStateFlow<ClearDataState>(ClearDataState.Idle)
    val clearDataState: StateFlow<ClearDataState> = _clearDataState.asStateFlow()

    // --- Event Flow ---
    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        checkBiometricSupport()
    }

    fun onSetPinClicked() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.NavigateToPinSetupOrChange)
        }
    }

    // --- Biometric Check ---
    private fun checkBiometricSupport() {
        viewModelScope.launch {
            val biometricManager = BiometricManager.from(context)
            // Check for strong biometrics or device credentials
            val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

            _biometricStatus.value = when (canAuthenticate) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricSupportStatus.READY
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricSupportStatus.NOT_SUPPORTED
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricSupportStatus.TEMPORARILY_UNAVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricSupportStatus.NEEDS_ENROLLMENT
                else -> BiometricSupportStatus.UNKNOWN_ERROR // Handle other potential statuses
            }
            Log.d(Constants.APP_TAG, "Biometric Check Result: ${_biometricStatus.value}")
        }
    }

    enum class BiometricSupportStatus {
        CHECKING,
        READY, // Hardware available and enrolled
        NOT_SUPPORTED, // No hardware
        TEMPORARILY_UNAVAILABLE, // Hardware unavailable
        NEEDS_ENROLLMENT, // Hardware available, but no biometrics enrolled
        UNKNOWN_ERROR
    }

    // --- Actions from Fragment ---

    fun onAppThemeChanged(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
            // Note: Actual theme application likely happens elsewhere (Application/Activity)
            // observing this preference change.
        }
    }

    fun onLockMethodSelected(method: LockMethod) {
        viewModelScope.launch {
            // If selecting PIN and maybe need to enforce setting one up?
            if (method == LockMethod.PIN) {
                // TODO: Check if PIN is already set, if not, emit NavigateToSetPin event?
                // For now, just save the preference. PIN setup is a separate flow.
                // _uiEvents.emit(UiEvent.NavigateToSetPin)
            }
            // If selecting BIOMETRIC, ensure it's supported/enrolled first
            if (method == LockMethod.BIOMETRIC && _biometricStatus.value != BiometricSupportStatus.READY) {
                _uiEvents.emit(UiEvent.ShowToast("Biometrics not available or not enrolled."))
                // Revert selection? Or let UI handle disabling the option? UI should disable based on biometricStatus.
                return@launch // Prevent setting unsupported method
            }
            settingsRepository.setLockMethod(method)
        }
    }

    fun onClearAllDataConfirmed() {
        viewModelScope.launch { // Keep UI responsive, actual deletion on IO
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
            // Reset state after showing feedback (maybe after a short delay?)
            delay(200) // Allow transient Success/Error state to be briefly observed
            _clearDataState.value = ClearDataState.Idle
        }
    }
}