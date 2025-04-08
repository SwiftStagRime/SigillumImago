package com.swifstagrime.feature_auth.ui.activities

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_data_api.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data object Setup_PromptPin : AuthState
    data class Setup_EnteringPin(val enteredDigits: Int) : AuthState
    data object Setup_PromptConfirmPin : AuthState
    data class Setup_EnteringConfirmPin(val enteredDigits: Int) : AuthState
    data object Setup_SavingPin : AuthState
    data object Setup_Success : AuthState
    data class Setup_Error(val messageResId: Int) : AuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _pinValue = MutableStateFlow("")
    private var _firstPinAttempt: String? = null

    private val _isBiometricAvailable = MutableStateFlow(false)
    val isBiometricAvailable: StateFlow<Boolean> = _isBiometricAvailable.asStateFlow()

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
                        _authState.value = AuthState.Setup_PromptPin
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

    private fun checkBiometricStatus() {
        when (biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> _isBiometricAvailable.value = true
            else -> _isBiometricAvailable.value = false
        }
    }


    fun onDigitEntered(digit: Int) {
        val currentState = _authState.value
        if (_pinValue.value.length >= requiredPinLength || !isPinEntryState(currentState)) {
            return
        }

        _pinValue.value += digit.toString()
        val currentLength = _pinValue.value.length

        when (currentState) {
            is AuthState.PromptPin, is AuthState.PinEntry, is AuthState.Error -> {
                _authState.value = AuthState.PinEntry(currentLength)
                if (currentLength == requiredPinLength) {
                    verifyPin()
                }
            }

            is AuthState.Setup_PromptPin, is AuthState.Setup_EnteringPin, is AuthState.Setup_Error -> {
                _authState.value = AuthState.Setup_EnteringPin(currentLength)
                if (currentLength == requiredPinLength) {
                    _firstPinAttempt = _pinValue.value
                    _pinValue.value = ""
                    _authState.value = AuthState.Setup_PromptConfirmPin
                    Log.d(Constants.APP_TAG, "First PIN entered, prompting for confirmation.")
                }
            }

            is AuthState.Setup_PromptConfirmPin, is AuthState.Setup_EnteringConfirmPin -> {
                _authState.value = AuthState.Setup_EnteringConfirmPin(currentLength)
                if (currentLength == requiredPinLength) {
                    verifyAndSetPin()
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

                is AuthState.Setup_EnteringPin, is AuthState.Setup_PromptPin, is AuthState.Setup_Error -> _authState.value =
                    AuthState.Setup_EnteringPin(currentLength)

                is AuthState.Setup_EnteringConfirmPin, is AuthState.Setup_PromptConfirmPin -> _authState.value =
                    AuthState.Setup_EnteringConfirmPin(currentLength)

                else -> {}
            }
        } else {
            when (currentState) {
                is AuthState.Error -> _authState.value = AuthState.PromptPin()
                is AuthState.Setup_Error -> _authState.value = AuthState.Setup_PromptPin
                else -> {}
            }
        }
    }

    private fun verifyAndSetPin() {
        val confirmationPin = _pinValue.value
        if (confirmationPin == _firstPinAttempt) {
            _authState.value = AuthState.Setup_SavingPin
            Log.d(Constants.APP_TAG, "Confirmation PIN matches. Attempting to save.")
            viewModelScope.launch(Dispatchers.IO) {
                authRepository.setPin(confirmationPin).fold(
                    onSuccess = {
                        Log.i(Constants.APP_TAG, "PIN set successfully.")
                        _authState.value = AuthState.Setup_Success
                        kotlinx.coroutines.delay(1000)
                        checkInitialState()
                    },
                    onFailure = {
                        Log.e(Constants.APP_TAG, "Failed to save PIN.", it)
                        _authState.value =
                            AuthState.Setup_Error(com.swifstagrime.core_ui.R.string.auth_pin_save_error)
                        resetSetupState()
                    }
                )
            }
        } else {
            Log.w(Constants.APP_TAG, "PIN confirmation mismatch.")
            _authState.value =
                AuthState.Setup_Error(com.swifstagrime.core_ui.R.string.auth_pin_mismatch)
            resetSetupState()
        }
    }

    private fun resetSetupState() {
        _pinValue.value = ""
        _firstPinAttempt = null
    }

    private fun verifyPin() {
        _authState.value = AuthState.VerifyingPin
        val currentPin = _pinValue.value
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.verifyPin(currentPin).fold(
                onSuccess = { isValid ->
                    if (isValid) {
                        _authState.value = AuthState.Success
                    } else {
                        _authState.value =
                            AuthState.Error(com.swifstagrime.core_ui.R.string.auth_incorrect_pin)
                        _pinValue.value = ""
                    }
                },
                onFailure = {
                    _authState.value =
                        AuthState.Error(com.swifstagrime.core_ui.R.string.auth_generic_error)
                    _pinValue.value = ""
                }
            )
        }
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
            is AuthState.Setup_PromptPin, is AuthState.Setup_EnteringPin, is AuthState.Setup_Error,
            is AuthState.Setup_PromptConfirmPin, is AuthState.Setup_EnteringConfirmPin -> true

            else -> false
        }
    }

    private fun isSetupState(state: AuthState): Boolean {
        return when (state) {
            is AuthState.Setup_PromptPin,
            is AuthState.Setup_EnteringPin,
            is AuthState.Setup_PromptConfirmPin,
            is AuthState.Setup_EnteringConfirmPin,
            is AuthState.Setup_SavingPin,
            is AuthState.Setup_Success,
            is AuthState.Setup_Error -> true

            else -> false
        }
    }


}