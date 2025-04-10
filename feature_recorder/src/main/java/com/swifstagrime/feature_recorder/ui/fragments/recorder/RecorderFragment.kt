package com.swifstagrime.feature_recorder.ui.fragments.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_ui.R
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_recorder.databinding.FragmentRecorderBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecorderFragment : BaseFragment<FragmentRecorderBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentRecorderBinding
        get() = FragmentRecorderBinding::inflate

    private val viewModel: RecorderViewModel by viewModels()
    private var isUserSeeking = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            viewModel.onPermissionCheckResult(isGranted)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun setupViews() {
        binding.recordStopButton.setOnClickListener { viewModel.onRecordStopClicked() }
        binding.playButton.setOnClickListener { viewModel.onPlayClicked() }
        binding.pauseButton.setOnClickListener { viewModel.onPauseClicked() }
        binding.saveButton.setOnClickListener { showSaveDialog() }
        binding.discardButton.setOnClickListener { showDiscardDialog() }
        binding.playbackSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.durationTextView.text = formatDurationMillis(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.progress?.let { finalProgress ->
                    viewModel.seekPlaybackTo(finalProgress.toLong())
                }
            }
        })
    }

    override fun observeViewModel() {
        viewModel.checkInitialState()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.permissionState.collect { state ->
                        handlePermissionState(state)
                    }
                }
                launch {
                    viewModel.recorderState.collect { state ->
                        updateUiForRecorderState(state)
                    }
                }
                launch {
                    viewModel.playbackState.collect { state ->
                        updateUiForPlaybackState(state)
                    }
                }
                launch {
                    combine(
                        viewModel.currentPosition,
                        viewModel.recordingDuration
                    ) { pos, dur -> pos to dur }
                        .distinctUntilChanged()
                        .collect { (position, duration) ->
                            if (!isUserSeeking) {
                                updateDurationDisplay(
                                    position,
                                    duration,
                                    viewModel.recorderState.value,
                                    viewModel.playbackState.value
                                )
                            }
                            if (!isUserSeeking) {
                                updateSeekBar(position, duration, viewModel.playbackState.value)
                            }
                        }
                }
            }
        }
    }

    private fun updateUiForRecorderState(state: RecorderState) {
        Log.d(Constants.APP_TAG, "Updating UI for RecorderState: $state")
        binding.statusTextView.isVisible =
            state is RecorderState.Error || state is RecorderState.Saving || state is RecorderState.RequestingPermission

        when (state) {
            RecorderState.Idle -> {
                binding.recordStopButton.setImageResource(R.drawable.ic_mic)
                binding.recordStopButton.isEnabled = true
                binding.saveButton.isVisible = false
                binding.discardButton.isVisible = false
                binding.statusTextView.isVisible = false
                resetPlaybackControls()
            }

            RecorderState.RequestingPermission -> {
                binding.recordStopButton.isEnabled = false
                binding.statusTextView.text = "Requesting permission..."
                binding.statusTextView.isVisible = true
            }

            RecorderState.Recording -> {
                binding.recordStopButton.setImageResource(R.drawable.ic_stop)
                binding.recordStopButton.isEnabled = true
                binding.saveButton.isVisible = false
                binding.discardButton.isVisible = false
                binding.statusTextView.isVisible = false
                resetPlaybackControls()
            }

            is RecorderState.Stopped -> {
                binding.recordStopButton.setImageResource(R.drawable.ic_mic)
                binding.recordStopButton.isEnabled = false
                binding.saveButton.isVisible = true
                binding.discardButton.isVisible = true
                binding.statusTextView.isVisible = false
            }

            RecorderState.Saving -> {
                binding.recordStopButton.isEnabled = false
                binding.saveButton.isVisible = false
                binding.discardButton.isVisible = false
                binding.statusTextView.text = getString(R.string.saving_recording)
                binding.statusTextView.isVisible = true
                resetPlaybackControls()
            }

            is RecorderState.Error -> {
                binding.recordStopButton.setImageResource(R.drawable.ic_mic)
                binding.recordStopButton.isEnabled =
                    viewModel.permissionState.value == PermissionState.Granted
                binding.saveButton.isVisible = false
                binding.discardButton.isVisible = false
                binding.statusTextView.text = state.message
                binding.statusTextView.isVisible = true
                resetPlaybackControls()
            }
        }
    }

    private fun updateUiForPlaybackState(state: PlaybackState) {
        Log.d(Constants.APP_TAG, "Updating UI for PlaybackState: $state")
        val canPlay =
            state == PlaybackState.ReadyToPlay || state == PlaybackState.Paused || state == PlaybackState.Completed
        val isPlaying = state == PlaybackState.Playing
        val isPlaybackAvailable =
            state != PlaybackState.NotReady && state != PlaybackState.Preparing && state !is PlaybackState.Error

        binding.playButton.isVisible = canPlay && !isPlaying
        binding.playButton.isEnabled = canPlay && !isPlaying

        binding.pauseButton.isVisible = isPlaying
        binding.pauseButton.isEnabled = isPlaying

        binding.playbackSeekBar.isEnabled = isPlaybackAvailable
        binding.playbackSeekBar.isVisible = isPlaybackAvailable

        if (state is PlaybackState.Error) {
            binding.playButton.isVisible = false
            binding.pauseButton.isVisible = false
            Log.e(Constants.APP_TAG, "Playback Error: ${state.message}")
        }
    }

    private fun resetPlaybackControls() {
        binding.playButton.isVisible = false
        binding.pauseButton.isVisible = false
        binding.playbackSeekBar.isVisible = false
        binding.playbackSeekBar.progress = 0
        binding.playbackSeekBar.isEnabled = false
    }

    private fun updateDurationDisplay(
        position: Long,
        duration: Long,
        recorderState: RecorderState,
        playbackState: PlaybackState
    ) {
        val displayMillis = when {
            recorderState is RecorderState.Recording -> duration
            playbackState == PlaybackState.Playing || playbackState == PlaybackState.Paused -> position
            recorderState is RecorderState.Stopped || playbackState == PlaybackState.Completed || playbackState == PlaybackState.ReadyToPlay -> duration
            else -> 0L
        }
        binding.durationTextView.text = formatDurationMillis(displayMillis)
    }

    private fun updateSeekBar(position: Long, duration: Long, playbackState: PlaybackState) {
        val isPlaybackAvailable =
            playbackState != PlaybackState.NotReady && playbackState != PlaybackState.Preparing && playbackState !is PlaybackState.Error

        if (duration > 0 && isPlaybackAvailable) {
            binding.playbackSeekBar.max = duration.toInt()
            binding.playbackSeekBar.progress = position.toInt()
            binding.playbackSeekBar.isVisible = true
            binding.playbackSeekBar.isEnabled = true
        } else {
            binding.playbackSeekBar.isVisible = false
            binding.playbackSeekBar.isEnabled = false
        }
    }

    private fun handlePermissionState(state: PermissionState) {
        when (state) {
            PermissionState.Granted -> {
                binding.statusTextView.isVisible = false
            }

            PermissionState.Denied -> {
                binding.statusTextView.text = getString(R.string.recording_permission_denied)
                binding.statusTextView.isVisible = true
            }

            PermissionState.NeedsRationale -> {
                showPermissionRationale()
            }

            PermissionState.Idle -> {
                binding.statusTextView.isVisible = false
            }
        }
        if (viewModel.recorderState.value is RecorderState.RequestingPermission) {
            requestAudioPermission()
        }
    }

    private fun requestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.onPermissionCheckResult(true)
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionRationale()
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(
            requireContext(),
            com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded
        )
            .setTitle("Permission Required")
            .setMessage(R.string.recording_permission_rationale)
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.onPermissionCheckResult(false)
            }
            .setPositiveButton("Grant") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .show()
    }

    private fun showDiscardDialog() {
        MaterialAlertDialogBuilder(
            requireContext(),
            com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded
        )
            .setTitle(R.string.dialog_discard_title)
            .setMessage(R.string.dialog_discard_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.discard) { _, _ ->
                viewModel.onDiscardConfirmed()
            }
            .show()
    }

    private fun showSaveDialog() {
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.default_recording_name)
        }

        MaterialAlertDialogBuilder(
            requireContext(),
            com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded
        )
            .setTitle(R.string.dialog_save_title)
            .setMessage(R.string.dialog_save_message)
            .setView(editText)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val enteredName = editText.text.toString()
                viewModel.onSaveConfirmed(enteredName)
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}