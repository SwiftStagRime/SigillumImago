package com.swifstagrime.feature_auth.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_auth.databinding.ActivityAuthBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels()

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var pinDots: List<ImageView>

    companion object {
        const val EXTRA_AUTH_MODE = "auth_mode_extra"
        const val AUTH_MODE_VERIFY = "verify"
        const val AUTH_MODE_SETUP_OR_CHANGE = "setup_or_change"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinDots = listOf(
            binding.pinDot1,
            binding.pinDot2,
            binding.pinDot3,
            binding.pinDot4,
            binding.pinDot5
        )

        handleIntentExtras()
        setupPinPad()
        setupBiometrics()
        observeViewModel()
    }

    private fun setupPinPad() {
        val numberButtons = mapOf(
            binding.button0 to 0, binding.button1 to 1, binding.button2 to 2,
            binding.button3 to 3, binding.button4 to 4, binding.button5 to 5,
            binding.button6 to 6, binding.button7 to 7, binding.button8 to 8,
            binding.button9 to 9
        )

        numberButtons.forEach { (button, digit) ->
            button.setOnClickListener { viewModel.onDigitEntered(digit) }
        }

        binding.buttonBackspace.setOnClickListener { viewModel.onBackspacePressed() }
        binding.buttonBiometric.setOnClickListener { viewModel.onBiometricAuthenticationRequested() }
    }

    private fun handleIntentExtras() {
        val authMode = intent.getStringExtra(EXTRA_AUTH_MODE) ?: AUTH_MODE_VERIFY

        if (authMode == AUTH_MODE_SETUP_OR_CHANGE) {
            viewModel.startPinSetupOrChangeFlow()
        }
    }

    private fun setupBiometrics() {
        executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                viewModel.onBiometricAuthenticationResult(false, errorCode, errString)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                viewModel.onBiometricAuthenticationResult(true, null, null)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }

        biometricPrompt = BiometricPrompt(this, executor, callback)

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(com.swifstagrime.core_ui.R.string.auth_biometric_prompt_title))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .setConfirmationRequired(false)
            .build()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.authState.collect { state ->
                        updateUiForState(state)
                    }
                }
                launch {
                    viewModel.isBiometricAuthOptionAvailable.collect { available ->
                        binding.buttonBiometric.visibility =
                            if (available) View.VISIBLE else View.INVISIBLE
                        binding.buttonBiometric.isEnabled = available
                    }
                }
            }
        }
    }

    private fun updateUiForState(state: AuthState) {
        val isSetupOrChange = when (state) {
            is AuthState.SetupPromptPin, is AuthState.SetupEnteringPin,
            is AuthState.SetupPromptConfirmPin, is AuthState.SetupEnteringConfirmPin,
            is AuthState.SetupSavingPin, is AuthState.SetupSuccess,
            is AuthState.SetupError,
            is AuthState.ChangePromptCurrentPin, is AuthState.ChangeEnteringCurrentPin,
            is AuthState.ChangeVerifyingCurrentPin -> true

            else -> false
        }

        val isPinPadEnabled = when (state) {
            is AuthState.VerifyingPin,
            is AuthState.SetupSavingPin,
            is AuthState.PromptBiometric,
            is AuthState.ChangeVerifyingCurrentPin -> false

            else -> true
        }
        setPinPadEnabled(isPinPadEnabled)

        when (state) {
            is AuthState.Idle -> {
                binding.statusTextView.text = ""
                resetPinDots()
            }

            is AuthState.PromptPin -> {
                binding.statusTextView.text = getString(state.messageResId)
                resetPinDots()
            }

            is AuthState.PinEntry -> {
                binding.statusTextView.text = getString(R.string.auth_enter_pin)
                updatePinDots(state.enteredDigits)
            }

            is AuthState.VerifyingPin -> {
                binding.statusTextView.text = getString(R.string.verifying_pin)
            }

            is AuthState.PromptBiometric -> {
                binding.statusTextView.text = getString(R.string.auth_use_biometrics)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    biometricPrompt.authenticate(promptInfo)
                }
            }

            is AuthState.VerifyingBiometric -> {}
            is AuthState.Success -> {
                navigateToMainApp()
            }

            is AuthState.Error -> {
                binding.statusTextView.text =
                    getString(state.messageResId, *(state.args?.toTypedArray() ?: emptyArray()))
                resetPinDots()
            }

            is AuthState.ChangePromptCurrentPin -> {
                binding.statusTextView.text = getString(state.messageResId)
                resetPinDots()
            }

            is AuthState.ChangeEnteringCurrentPin -> {
                binding.statusTextView.text = getString(R.string.auth_enter_pin)
                updatePinDots(state.enteredDigits)
            }

            is AuthState.ChangeVerifyingCurrentPin -> {
                binding.statusTextView.text = getString(R.string.verifying_pin)
            }

            is AuthState.SetupPromptPin -> {
                binding.statusTextView.text = getString(R.string.auth_pin_setup_prompt)
                resetPinDots()
            }

            is AuthState.SetupEnteringPin -> {
                binding.statusTextView.text = getString(R.string.auth_pin_setup_prompt)
                updatePinDots(state.enteredDigits)
            }

            is AuthState.SetupPromptConfirmPin -> {
                binding.statusTextView.text = getString(R.string.auth_pin_confirm_prompt)
                resetPinDots()
            }

            is AuthState.SetupEnteringConfirmPin -> {
                binding.statusTextView.text = getString(R.string.auth_pin_confirm_prompt)
                updatePinDots(state.enteredDigits)
            }

            is AuthState.SetupSavingPin -> {
                binding.statusTextView.text = getString(R.string.auth_pin_saving)
            }

            is AuthState.SetupSuccess -> {
                binding.statusTextView.text = getString(R.string.auth_pin_set_success)
            }

            is AuthState.SetupError -> {
                binding.statusTextView.text = getString(state.messageResId)
                resetPinDots()
            }

            is AuthState.BiometricsAvailable,
            is AuthState.BiometricsUnavailable,
            is AuthState.BiometricsNotSetup -> {
            }
        }
    }

    private fun setPinPadEnabled(enabled: Boolean) {
        binding.button0.isEnabled = enabled
        binding.button1.isEnabled = enabled
        binding.button2.isEnabled = enabled
        binding.button3.isEnabled = enabled
        binding.button4.isEnabled = enabled
        binding.button5.isEnabled = enabled
        binding.button6.isEnabled = enabled
        binding.button7.isEnabled = enabled
        binding.button8.isEnabled = enabled
        binding.button9.isEnabled = enabled

        binding.buttonBackspace.isEnabled = enabled
    }

    private fun updatePinDots(filledCount: Int) {
        pinDots.forEachIndexed { index, imageView ->
            val drawableRes = if (index < filledCount) {
                com.swifstagrime.core_ui.R.drawable.ic_dot_fill
            } else {
                com.swifstagrime.core_ui.R.drawable.ic_dot
            }
            imageView.setImageResource(drawableRes)
        }
    }

    private fun resetPinDots() {
        updatePinDots(0)
    }

    private fun navigateToMainApp() {
        val mainActivityClassName = "com.swifstagrime.sigillumimago.MainActivity"

        try {
            val mainActivityClass = Class.forName(mainActivityClassName)
            val intent = Intent(this, mainActivityClass)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (_: Exception) {
            binding.statusTextView.text = ContextCompat.getString(
                this,
                com.swifstagrime.core_ui.R.string.error_starting_main_activity
            )
            binding.statusTextView.setTextColor(
                ContextCompat.getColor(
                    this,
                    com.swifstagrime.core_ui.R.color.error_light
                )
            )
            setPinPadEnabled(false)
        }
    }


}