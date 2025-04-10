package com.swifstagrime.feature_settings.ui.fragments.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.swifstagrime.core_ui.R
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.feature_auth.ui.activities.AuthActivity
import com.swifstagrime.feature_settings.domain.models.AppTheme
import com.swifstagrime.feature_settings.domain.models.LockMethod
import com.swifstagrime.feature_settings.ui.fragments.settings.SettingsViewModel.BiometricSupportStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var keyAppTheme: String
    private lateinit var keyEnableAppLock: String
    private lateinit var keyLockMethod: String
    private lateinit var keySetPin: String
    private lateinit var keyStorageUsage: String
    private lateinit var keyClearData: String
    private lateinit var keyVersion: String
    private lateinit var keyPrivacyPolicy: String
    private lateinit var keyLicenses: String

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(com.swifstagrime.feature_settings.R.xml.preferences, rootKey)
        initializeKeys()
        setupPreferenceListeners()
        updateVersionSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()
    }

    private fun initializeKeys() {
        keyAppTheme = getString(R.string.settings_key_app_theme)
        keyEnableAppLock = getString(R.string.settings_key_enable_app_lock)
        keyLockMethod = getString(R.string.settings_key_lock_method)
        keySetPin = getString(R.string.settings_key_set_pin)
        keyStorageUsage = getString(R.string.settings_key_storage_usage)
        keyClearData = getString(R.string.settings_key_clear_data)
        keyVersion = getString(R.string.settings_key_version)
        keyPrivacyPolicy = getString(R.string.settings_key_privacy_policy)
        keyLicenses = getString(R.string.settings_key_licenses)
    }

    private fun setupPreferenceListeners() {
        findPreference<ListPreference>(keyAppTheme)?.setOnPreferenceChangeListener { _, newValue ->
            val selectedTheme = AppTheme.valueOf(newValue as String)
            viewModel.onAppThemeChanged(selectedTheme)
            true
        }

        findPreference<SwitchPreferenceCompat>(keyEnableAppLock)?.setOnPreferenceChangeListener { _, newValue ->
            val isEnabled = newValue as Boolean
            val currentMethod = viewModel.lockMethod.value
            val newMethod = if (isEnabled) {
                if (currentMethod == LockMethod.NONE) LockMethod.PIN else currentMethod
            } else {
                LockMethod.NONE
            }
            viewModel.onLockMethodSelected(newMethod)
            true
        }

        findPreference<ListPreference>(keyLockMethod)?.setOnPreferenceChangeListener { _, newValue ->
            val selectedMethod = LockMethod.valueOf(newValue as String)
            viewModel.onLockMethodSelected(selectedMethod)
            true
        }

        findPreference<Preference>(keySetPin)?.setOnPreferenceClickListener {
            viewModel.onSetPinClicked()
            true
        }

        findPreference<Preference>(keyClearData)?.setOnPreferenceClickListener {
            showClearDataConfirmationDialog()
            true
        }

        findPreference<Preference>(keyLicenses)?.setOnPreferenceClickListener {
            showToast("Licenses screen not yet implemented.")
            true
        }
    }


    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.appTheme.collect { theme ->
                        findPreference<ListPreference>(keyAppTheme)?.value = theme.name
                    }
                }

                launch {
                    viewModel.lockMethod.collect { method ->
                        updateLockMethodPreference(method, viewModel.biometricStatus.value)
                    }
                }

                launch {
                    viewModel.isAppLockEnabled.collect { isEnabled ->
                        findPreference<SwitchPreferenceCompat>(keyEnableAppLock)?.isChecked = isEnabled
                        findPreference<ListPreference>(keyLockMethod)?.isVisible = isEnabled
                        findPreference<Preference>(keySetPin)?.isVisible = isEnabled
                    }
                }

                launch {
                    viewModel.isPinSet.collect { isSet ->
                        findPreference<Preference>(keySetPin)?.apply {
                            title = if(isSet) getString(R.string.settings_title_change_pin) else getString(R.string.settings_title_set_pin)
                            summary = if(isSet) getString(R.string.settings_summary_change_pin) else getString(R.string.settings_summary_set_pin_new)
                        }
                    }
                }


                launch {
                    viewModel.biometricStatus.collect { status ->
                        updateLockMethodPreference(viewModel.lockMethod.value, status)
                    }
                }

                launch {
                    viewModel.clearDataState.collect { state ->
                        handleClearDataState(state)
                    }
                }

                launch {
                    viewModel.uiEvents.collect { event ->
                        when (event) {
                            UiEvent.NavigateToPinSetupOrChange -> navigateToAuthActivityForSetup()
                            is UiEvent.ShowToast -> showToast(event.message)
                            is UiEvent.ShowErrorSnackbar -> showSnackbar(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun navigateToAuthActivityForSetup() {
        val intent = Intent(requireContext(), AuthActivity::class.java).apply {
            putExtra(AuthActivity.EXTRA_AUTH_MODE, AuthActivity.AUTH_MODE_SETUP_OR_CHANGE)
        }
        startActivity(intent)
    }

    private fun updateLockMethodPreference(method: LockMethod, bioStatus: BiometricSupportStatus) {
        val lockMethodPref = findPreference<ListPreference>(keyLockMethod)
        val setPinPref = findPreference<Preference>(keySetPin)
        val isLockEnabled = method != LockMethod.NONE

        lockMethodPref?.isVisible = isLockEnabled
        setPinPref?.isVisible = isLockEnabled

        if (isLockEnabled) {
            lockMethodPref?.value = method.name

            val entries = resources.getStringArray(com.swifstagrime.feature_settings.R.array.lock_method_entries).toMutableList()
            val entryValues = resources.getStringArray(com.swifstagrime.feature_settings.R.array.lock_method_values).toMutableList()

            val biometricValue = LockMethod.BIOMETRIC.name
            val biometricIndex = entryValues.indexOf(biometricValue)

            if (bioStatus != BiometricSupportStatus.READY && biometricIndex != -1) {
                entries.removeAt(biometricIndex)
                entryValues.removeAt(biometricIndex)
                Log.d(Constants.APP_TAG, "Biometrics not ready ($bioStatus), removing option.")
            }

            lockMethodPref?.entries = entries.toTypedArray()
            lockMethodPref?.entryValues = entryValues.toTypedArray()

            setPinPref?.isEnabled = (method == LockMethod.PIN)
            setPinPref?.summary = if (method == LockMethod.PIN) {
                getString(R.string.settings_summary_set_pin)
            } else {
                ""
            }

        } else {
            setPinPref?.summary = ""
        }
    }

    private fun handleClearDataState(state: ClearDataState) {
        when (state) {
            ClearDataState.Clearing -> showToast(getString(R.string.clearing_data))
            ClearDataState.Success -> { }
            is ClearDataState.Error -> { }
            ClearDataState.Idle -> { }
        }
    }

    private fun showClearDataConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_Dialog_Rounded)
            .setTitle(R.string.dialog_clear_data_title)
            .setMessage(R.string.dialog_clear_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                viewModel.onClearAllDataConfirmed()
            }
            .show()
    }

    private fun updateVersionSummary() {
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = packageInfo.versionName
            findPreference<Preference>(keyVersion)?.summary = version
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(Constants.APP_TAG, "Could not get package info", e)
            findPreference<Preference>(keyVersion)?.summary = "N/A"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }


}