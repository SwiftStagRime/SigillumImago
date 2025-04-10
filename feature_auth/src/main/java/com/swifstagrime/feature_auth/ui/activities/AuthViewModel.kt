package com.swifstagrime.feature_auth.ui.activities

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.LockMethod
import com.swifstagrime.core_data_api.repository.AuthRepository
import com.swifstagrime.core_data_api.repository.SettingsRepository
import com.swifstagrime.core_ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthState {
    data object Idle : AuthState
    data class PromptPin(val messageResId: Int = com.swifstagrime.core_ui.R.string.auth_enter_pin) :
        AuthState

    data class PinEntry(val enteredDigits: Int) : AuthState
    data object VerifyingPin : AuthState
    data object PromptBiometric : AuthState
    data object VerifyingBiometric : AuthState
    data object Success : AuthState
    data class Error(val messageResId: Int, val args: List<Any>? = null) : AuthState
    data object BiometricsAvailable : AuthState
    data object BiometricsUnavailable : AuthState
    data object BiometricsNotSetup : AuthState
    data object SetupPromptPin : AuthState
    data class SetupEnteringPin(val enteredDigits: Int) : AuthState
    data object SetupPromptConfirmPin : AuthState
    data class SetupEnteringConfirmPin(val enteredDigits: Int) : AuthState
    data object SetupSavingPin : AuthState
    data object SetupSuccess : AuthState
    data class SetupError(val messageResId: Int) : AuthState
    data class ChangePromptCurrentPin(val messageResId: Int = com.swifstagrime.core_ui.R.string.auth_enter_pin) :
        AuthState

    data class ChangeEnteringCurrentPin(val enteredDigits: Int) : AuthState
    data object ChangeVerifyingCurrentPin : AuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _pinValue = MutableStateFlow("")
    private var _firstPinAttempt: String? = null

    private val _isBiometricAvailable = MutableStateFlow(false)

    val isBiometricAuthOptionAvailable: StateFlow<Boolean> = combine(
        _isBiometricAvailable,
        settingsRepository.lockMethod
    ) { hardwareAvailable, lockMethod ->
        hardwareAvailable && (lockMethod == LockMethod.BIOMETRIC)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private val biometricManager = BiometricManager.from(context)
    private val requiredPinLength = 5

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        _pinValue.value = ""
        _firstPinAttempt = null
        viewModelScope.launch(Dispatchers.IO) {
            checkBiometricStatus()

            authRepository.isPinSet().fold(
                onSuccess = { isSet ->
                    if (isSet) {
                        _authState.value = AuthState.PromptPin()
                    } else {
                        _authState.value = AuthState.SetupPromptPin
                        Log.i(Constants.APP_TAG, "No PIN set. Starting setup flow.")
                    }
                },
                onFailure = {
                    _authState.value =
                        AuthState.Error(com.swifstagrime.core_ui.R.string.auth_generic_error)
                    Log.e(Constants.APP_TAG, "Failed to check if PIN is set", it)
                }
            )
        }
    }

    fun startPinSetupOrChangeFlow() {
        _pinValue.value = ""
        _firstPinAttempt = null
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.isPinSet().fold(
                onSuccess = { isSet ->
                    if (isSet) {
                        _authState.value = AuthState.ChangePromptCurrentPin()
                        Log.i(Constants.APP_TAG, "PIN exists. Starting CHANGE flow.")
                    } else {
                        _authState.value = AuthState.SetupPromptPin
                        Log.i(Constants.APP_TAG, "No PIN set. Starting SETUP flow.")
                    }
                },
                onFailure = {
                    _authState.value = AuthState.Error(R.string.auth_generic_error)
                    Log.e(
                        Constants.APP_TAG,
                        "Failed to check if PIN is set for setup/change flow",
                        it
                    )
                }
            )
        }
    }

    private fun checkBiometricStatus() {
        when (biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> _isBiometricAvailable.value = true
            else -> _isBiometricAvailable.value = false
        }
    }

    fun onDigitEntered(digit: Int) {
        val currentState = _authState.value
        val isPinComplete = _pinValue.value.length + 1 == requiredPinLength

        if (_pinValue.value.length >= requiredPinLength || !isPinEntryState(currentState)) {
            return
        }

        _pinValue.value += digit.toString()
        val currentLength = _pinValue.value.length

        when (currentState) {
            is AuthState.PromptPin, is AuthState.PinEntry, is AuthState.Error -> {
                _authState.value = AuthState.PinEntry(currentLength)
                if (isPinComplete) {
                    verifyPinForLogin()
                }
            }

            is AuthState.ChangePromptCurrentPin, is AuthState.ChangeEnteringCurrentPin -> {
                _authState.value = AuthState.ChangeEnteringCurrentPin(currentLength)
                if (isPinComplete) {
                    verifyCurrentPinForChange()
                }
            }

            is AuthState.SetupPromptPin, is AuthState.SetupEnteringPin, is AuthState.SetupError -> {
                _authState.value = AuthState.SetupEnteringPin(currentLength)
                if (isPinComplete) {
                    _firstPinAttempt = _pinValue.value
                    _pinValue.value = ""
                    _authState.value = AuthState.SetupPromptConfirmPin
                    Log.d(Constants.APP_TAG, "First new PIN entered, prompting for confirmation.")
                }
            }

            is AuthState.SetupPromptConfirmPin, is AuthState.SetupEnteringConfirmPin -> {
                _authState.value = AuthState.SetupEnteringConfirmPin(currentLength)
                if (isPinComplete) {
                    verifyAndSetNewPin()
                }
            }

            else -> {}
        }
    }

    fun onBackspacePressed() {
        val currentState = _authState.value
        if (_pinValue.value.isNotEmpty()) {
            _pinValue.value = _pinValue.value.dropLast(1)
            val currentLength = _pinValue.value.length

            when (currentState) {
                is AuthState.PinEntry, is AuthState.PromptPin, is AuthState.Error -> _authState.value =
                    AuthState.PinEntry(currentLength)

                is AuthState.ChangeEnteringCurrentPin, is AuthState.ChangePromptCurrentPin -> _authState.value =
                    AuthState.ChangeEnteringCurrentPin(currentLength)

                is AuthState.SetupEnteringPin, is AuthState.SetupPromptPin, is AuthState.SetupError -> _authState.value =
                    AuthState.SetupEnteringPin(currentLength)

                is AuthState.SetupEnteringConfirmPin, is AuthState.SetupPromptConfirmPin -> _authState.value =
                    AuthState.SetupEnteringConfirmPin(currentLength)

                else -> {}
            }
        } else {
            when (currentState) {
                is AuthState.Error -> _authState.value = AuthState.PromptPin()
                is AuthState.SetupError -> _authState.value = AuthState.SetupPromptPin
                else -> {}
            }
        }
    }

    private fun verifyPinForLogin() {
        _authState.value = AuthState.VerifyingPin
        val currentPin = _pinValue.value
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.verifyPin(currentPin).fold(
                onSuccess = { isValid ->
                    if (isValid) {
                        _authState.value = AuthState.Success
                    } else {
                        _authState.value = AuthState.Error(R.string.auth_incorrect_pin)
                        _pinValue.value = ""
                    }
                },
                onFailure = {
                    _authState.value = AuthState.Error(R.string.auth_generic_error)
                    _pinValue.value = ""
                }
            )
        }
    }

    private fun verifyCurrentPinForChange() {
        _authState.value = AuthState.ChangeVerifyingCurrentPin
        val currentPin = _pinValue.value
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.verifyPin(currentPin).fold(
                onSuccess = { isValid ->
                    if (isValid) {
                        _pinValue.value = ""
                        _firstPinAttempt = null
                        _authState.value = AuthState.SetupPromptPin
                        Log.i(
                            Constants.APP_TAG,
                            "Current PIN verified for change. Prompting for new PIN."
                        )
                    } else {
                        _authState.value =
                            AuthState.ChangePromptCurrentPin(R.string.auth_incorrect_pin)
                        _pinValue.value = ""
                        Log.w(
                            Constants.APP_TAG,
                            "Incorrect current PIN entered during change attempt."
                        )
                    }
                },
                onFailure = {
                    _authState.value = AuthState.ChangePromptCurrentPin(R.string.auth_generic_error)
                    _pinValue.value = ""
                    Log.e(Constants.APP_TAG, "Error verifying current PIN for change", it)
                }
            )
        }
    }

    private fun verifyAndSetNewPin() {
        val confirmationPin = _pinValue.value
        if (confirmationPin == _firstPinAttempt) {
            _authState.value = AuthState.SetupSavingPin
            Log.d(Constants.APP_TAG, "Confirmation PIN matches. Attempting to save.")
            viewModelScope.launch(Dispatchers.IO) {
                authRepository.setPin(confirmationPin).fold(
                    onSuccess = {
                        Log.i(Constants.APP_TAG, "New PIN set successfully.")
                        _authState.value = AuthState.SetupSuccess
                        kotlinx.coroutines.delay(500)
                        _pinValue.value = ""
                        _firstPinAttempt = null
                        _authState.value = AuthState.PromptPin(R.string.auth_pin_set_success)
                    },
                    onFailure = {
                        Log.e(Constants.APP_TAG, "Failed to save new PIN.", it)
                        _authState.value = AuthState.SetupError(R.string.auth_pin_save_error)
                        resetSetupState()
                    }
                )
            }
        } else {
            Log.w(Constants.APP_TAG, "New PIN confirmation mismatch.")
            _authState.value = AuthState.SetupError(R.string.auth_pin_mismatch)
            resetSetupState()
        }
    }

    private fun resetSetupState() {
        _pinValue.value = ""
        _firstPinAttempt = null
    }

    fun onBiometricAuthenticationRequested() {
        if (_isBiometricAvailable.value && !isSetupState(_authState.value)) {
            _authState.value = AuthState.PromptBiometric
        }
    }

    fun onBiometricAuthenticationResult(
        success: Boolean,
        errorCode: Int?,
        errorString: CharSequence?
    ) {
        if (success) {
            _authState.value = AuthState.Success
        } else {
            Log.e(
                Constants.APP_TAG,
                "Biometric authentication failed. Code: $errorCode, Msg: $errorString"
            )
            _authState.value =
                AuthState.PromptPin(com.swifstagrime.core_ui.R.string.auth_biometric_failed)
        }
    }

    private fun isPinEntryState(state: AuthState): Boolean {
        return when (state) {
            is AuthState.PromptPin, is AuthState.PinEntry, is AuthState.Error,
            is AuthState.ChangePromptCurrentPin, is AuthState.ChangeEnteringCurrentPin,
            is AuthState.SetupPromptPin, is AuthState.SetupEnteringPin, is AuthState.SetupError,
            is AuthState.SetupPromptConfirmPin, is AuthState.SetupEnteringConfirmPin -> true

            else -> false
        }
    }

    private fun isSetupState(state: AuthState): Boolean {
        return when (state) {
            is AuthState.SetupPromptPin,
            is AuthState.SetupEnteringPin,
            is AuthState.SetupPromptConfirmPin,
            is AuthState.SetupEnteringConfirmPin,
            is AuthState.SetupSavingPin,
            is AuthState.SetupSuccess,
            is AuthState.SetupError -> true

            else -> false
        }
    }

    fun startPinSetupFlow() {
        _pinValue.value = ""
        _firstPinAttempt = null
        _authState.value = AuthState.SetupPromptPin
    }
}