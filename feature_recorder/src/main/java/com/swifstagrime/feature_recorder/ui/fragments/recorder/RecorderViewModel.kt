package com.swifstagrime.feature_recorder.ui.fragments.recorder

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_recorder.domain.services.AudioPlayerHelper
import com.swifstagrime.feature_recorder.domain.services.AudioRecorderHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed interface PermissionState {
    object Idle : PermissionState
    object Granted : PermissionState
    object Denied : PermissionState
    object NeedsRationale : PermissionState
}

sealed interface RecorderState {
    object Idle : RecorderState
    object RequestingPermission : RecorderState
    object Recording : RecorderState
    data class Stopped(val audioFile: File) : RecorderState
    object Saving : RecorderState
    data class Error(val message: String) : RecorderState
}

sealed interface PlaybackState {
    object NotReady : PlaybackState
    object Preparing : PlaybackState
    object ReadyToPlay : PlaybackState
    object Playing : PlaybackState
    object Paused : PlaybackState
    object Completed : PlaybackState
    data class Error(val message: String) : PlaybackState
}

@HiltViewModel
class RecorderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorderHelper: AudioRecorderHelper,
    private val playerHelper: AudioPlayerHelper,
    private val secureMediaRepository: SecureMediaRepository
) : ViewModel() {

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.Idle)
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    private val _recorderState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.NotReady)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var playbackProgressJob: Job? = null

    private var tempAudioFile: File? = null

    init {
        observeRecorderEvents()
        observePlayerEvents()
    }

    fun checkInitialState() {
        val currentState = _recorderState.value
        var needsReset = false
        if (currentState is RecorderState.Error) {
            Log.d(Constants.APP_TAG, "Resetting from previous Error state.")
            needsReset = true
        }

        if (needsReset) {
            resetToIdleState()
        }

        if (_permissionState.value == PermissionState.Idle || _recorderState.value == RecorderState.RequestingPermission) {
            if (_recorderState.value != RecorderState.RequestingPermission && _permissionState.value == PermissionState.Idle) {
                _recorderState.value = RecorderState.RequestingPermission
            }
        }
    }

    fun onPermissionCheckResult(isGranted: Boolean) {
        if (isGranted) {
            _permissionState.value = PermissionState.Granted
            if (_recorderState.value is RecorderState.RequestingPermission) {
                _recorderState.value = RecorderState.Idle
            }
        } else {
            _permissionState.value = PermissionState.Denied
            _recorderState.value = RecorderState.Error(
                context.getString(R.string.recording_permission_denied)
            )
        }
    }

    fun onRecordStopClicked() {
        when (_recorderState.value) {
            RecorderState.Idle -> startRecording()
            RecorderState.Recording -> stopRecording()
            else -> Log.w(
                Constants.APP_TAG,
                "Record/Stop clicked in invalid state: ${_recorderState.value}"
            )
        }
    }

    fun onPlayClicked() {
        val currentState = _playbackState.value
        Log.d(Constants.APP_TAG, "Play clicked. Current State: $currentState")

        if (currentState == PlaybackState.ReadyToPlay || currentState == PlaybackState.Paused) {
            playerHelper.play()
            _playbackState.value = PlaybackState.Playing
            startPlaybackProgressUpdates()
        } else if (currentState == PlaybackState.Completed) {
            try {
                playerHelper.seekTo(0)
                playerHelper.play()
                _playbackState.value = PlaybackState.Playing
                startPlaybackProgressUpdates()
            } catch (e: IllegalStateException) {
                Log.e(Constants.APP_TAG, "Failed to restart playback after completion", e)
                _playbackState.value =
                    PlaybackState.Error("Failed to restart playback: ${e.message}")
            }
        } else {
            Log.w(Constants.APP_TAG, "Play clicked in ignored state: $currentState")
        }
    }

    fun onPauseClicked() {
        if (_playbackState.value == PlaybackState.Playing) {
            playerHelper.pause()
            _playbackState.value = PlaybackState.Paused
            stopPlaybackProgressUpdates()
        } else {
            Log.w(Constants.APP_TAG, "Pause clicked in invalid state: ${_playbackState.value}")
        }
    }

    fun onSaveConfirmed(desiredName: String) {
        val currentState = _recorderState.value
        if (currentState is RecorderState.Stopped) {
            val fileToSave = currentState.audioFile
            val finalName =
                if (desiredName.isBlank()) context.getString(R.string.default_recording_name) else desiredName
            val fileNameWithExtension = if (finalName.endsWith(".m4a", ignoreCase = true)) {
                finalName
            } else {
                "$finalName.m4a"
            }

            viewModelScope.launch(Dispatchers.IO) {
                _recorderState.value = RecorderState.Saving
                _playbackState.value = PlaybackState.NotReady

                try {
                    val fileBytes = fileToSave.readBytes()
                    val result = secureMediaRepository.saveMedia(
                        desiredFileName = fileNameWithExtension,
                        mediaType = MediaType.AUDIO,
                        data = fileBytes
                    )

                    when (result) {
                        is Result.Success -> {
                            Log.i(Constants.APP_TAG, "Recording saved: ${result.data.fileName}")
                            withContext(Dispatchers.Main) {
                                resetToIdleState()
                            }
                            cleanupTempFile(fileToSave)
                        }

                        is Result.Error -> {
                            Log.e(Constants.APP_TAG, "Failed to save recording", result.exception)
                            withContext(Dispatchers.Main) {
                                _recorderState.value =
                                    RecorderState.Error("Save failed: ${result.exception.message}")
                                _recorderState.value = currentState
                                _playbackState.value = PlaybackState.ReadyToPlay
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(Constants.APP_TAG, "Failed to read temp file for saving", e)
                    withContext(Dispatchers.Main) {
                        _recorderState.value =
                            RecorderState.Error("Save failed: Could not read temp file.")
                        _recorderState.value = currentState
                        _playbackState.value = PlaybackState.NotReady
                    }
                }
            }
        } else {
            Log.w(Constants.APP_TAG, "Save requested in invalid state: ${_recorderState.value}")
        }
    }

    fun onDiscardConfirmed() {
        val currentState = _recorderState.value
        if (currentState is RecorderState.Stopped) {
            cleanupTempFile(currentState.audioFile)
        }
        resetToIdleState()
    }

    fun requestPermissionAgain() {
        if (_permissionState.value == PermissionState.Denied) {
            _recorderState.value = RecorderState.RequestingPermission
        }
    }

    private fun checkPermission(permission: String) {
        _recorderState.value = RecorderState.RequestingPermission
    }

    fun seekPlaybackTo(positionMs: Long) {
        val currentPlaybackState = _playbackState.value
        if (currentPlaybackState == PlaybackState.ReadyToPlay ||
            currentPlaybackState == PlaybackState.Playing ||
            currentPlaybackState == PlaybackState.Paused ||
            currentPlaybackState == PlaybackState.Completed
        ) {
            try {
                val seekPosition = positionMs.coerceAtLeast(0L)
                playerHelper.seekTo(seekPosition)
                _currentPosition.value = seekPosition
                if (currentPlaybackState == PlaybackState.Completed) {
                    _playbackState.value = PlaybackState.Paused
                    stopPlaybackProgressUpdates()
                }
                Log.d(
                    Constants.APP_TAG,
                    "Seeked to $seekPosition ms. State: ${_playbackState.value}"
                )

            } catch (e: IllegalStateException) {
                Log.e(Constants.APP_TAG, "Seek failed", e)
            }
        } else {
            Log.w(Constants.APP_TAG, "Seek ignored in state: $currentPlaybackState")
        }
    }

    private fun startRecording() {
        if (_permissionState.value != PermissionState.Granted) {
            checkPermission(Manifest.permission.RECORD_AUDIO)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                tempAudioFile = createTempAudioFile()
                recorderHelper.start(tempAudioFile!!)
                withContext(Dispatchers.Main) {
                    _recorderState.value = RecorderState.Recording
                    _playbackState.value = PlaybackState.NotReady
                    startRecordingTimer()
                }
            } catch (e: Exception) {
                Log.e(Constants.APP_TAG, "Failed to start recording", e)
                withContext(Dispatchers.Main) {
                    _recorderState.value = RecorderState.Error("Start failed: ${e.message}")
                }
                cleanupTempFile(tempAudioFile)
                tempAudioFile = null
            }
        }
    }

    private fun stopRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopRecordingTimer()
                recorderHelper.stop()
            } catch (e: Exception) {
                Log.e(Constants.APP_TAG, "Failed to stop recording", e)
                withContext(Dispatchers.Main) {
                    _recorderState.value = RecorderState.Error("Stop failed: ${e.message}")
                }
            }
        }
    }

    private fun preparePlayer(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _playbackState.value = PlaybackState.Preparing
                playerHelper.prepare(file)
            } catch (e: Exception) {
                Log.e(Constants.APP_TAG, "Failed to prepare player", e)
                withContext(Dispatchers.Main) {
                    _playbackState.value = PlaybackState.Error("Prepare failed: ${e.message}")
                }
            }
        }
    }

    private fun observeRecorderEvents() {
        viewModelScope.launch {
            recorderHelper.recordingEvents.collect { event ->
                Log.d(Constants.APP_TAG, "Recorder Event: $event")
                when (event) {
                    is AudioRecorderHelper.RecorderEvent.Completed -> {
                        _recorderState.value = RecorderState.Stopped(event.outputFile)
                        _recordingDuration.value = recorderHelper.getLastRecordingDuration()
                        preparePlayer(event.outputFile)
                    }

                    is AudioRecorderHelper.RecorderEvent.Error -> {
                        _recorderState.value = RecorderState.Error(
                            event.exception.message ?: "Unknown recording error"
                        )
                        stopRecordingTimer()
                        cleanupTempFile(tempAudioFile)
                        tempAudioFile = null
                    }
                }
            }
        }
    }

    private fun observePlayerEvents() {
        viewModelScope.launch {
            playerHelper.playbackEvents.collect { event ->
                Log.d(Constants.APP_TAG, "Player Event: $event")
                when (event) {
                    is AudioPlayerHelper.PlayerEvent.Prepared -> {
                        _playbackState.value = PlaybackState.ReadyToPlay
                        if (event.durationMs > 0) {
                            _recordingDuration.value = event.durationMs
                        }
                        _currentPosition.value = 0
                    }

                    is AudioPlayerHelper.PlayerEvent.Completion -> {
                        _playbackState.value = PlaybackState.Completed
                        _currentPosition.value = _recordingDuration.value
                        stopPlaybackProgressUpdates()
                    }

                    is AudioPlayerHelper.PlayerEvent.Error -> {
                        _playbackState.value =
                            PlaybackState.Error(event.exception.message ?: "Unknown playback error")
                        stopPlaybackProgressUpdates()
                    }

                    is AudioPlayerHelper.PlayerEvent.Progress -> {
                    }
                }
            }
        }
    }

    private fun startRecordingTimer() {
        stopRecordingTimer()
        _recordingDuration.value = 0L
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                _recordingDuration.value += 1000
                delay(1000)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    private fun startPlaybackProgressUpdates() {
        stopPlaybackProgressUpdates()
        playbackProgressJob = viewModelScope.launch {
            while (isActive) {
                try {
                    if (_playbackState.value == PlaybackState.Playing) {
                        _currentPosition.value = playerHelper.getCurrentPosition()
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    Log.w(Constants.APP_TAG, "Error getting player position", e)
                    break
                }
                delay(200)
            }
        }
    }

    private fun stopPlaybackProgressUpdates() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
    }

    private fun createTempAudioFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "RECORDING_${timestamp}.m4a"
        return File(context.cacheDir, fileName)
    }

    private fun cleanupTempFile(file: File?) {
        if (file != null && file.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val deleted = file.delete()
                    Log.d(Constants.APP_TAG, "Temp file ${file.name} deleted: $deleted")
                } catch (e: Exception) {
                    Log.e(Constants.APP_TAG, "Error deleting temp file ${file.name}", e)
                }
            }
        }
        tempAudioFile = null
    }

    private fun resetToIdleState() {
        stopRecordingTimer()
        stopPlaybackProgressUpdates()
        playerHelper.release()
        _recorderState.value = RecorderState.Idle
        _playbackState.value = PlaybackState.NotReady
        _recordingDuration.value = 0
        _currentPosition.value = 0
        cleanupTempFile(tempAudioFile)
        tempAudioFile = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(Constants.APP_TAG, "RecorderViewModel cleared")
        recorderHelper.release()
        playerHelper.release()
        stopRecordingTimer()
        stopPlaybackProgressUpdates()
        cleanupTempFile(tempAudioFile)
    }


}

fun formatDurationMillis(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return String.format("%02d:%02d", minutes, seconds)
}