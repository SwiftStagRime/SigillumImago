package com.swifstagrime.feature_recordings.data.services

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RecordingPlaybackServiceImpl @Inject constructor(
    private val secureMediaRepository: SecureMediaRepository,
) : RecordingPlaybackService {

    private val _playbackState =
        MutableStateFlow<RecordingPlaybackService.PlaybackState>(RecordingPlaybackService.PlaybackState.Idle)
    override val playbackState: StateFlow<RecordingPlaybackService.PlaybackState> =
        _playbackState.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentFileName: String? = null
    private var currentDurationMs: Long = 0L
    private var isPrepared = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressPollingJob: Job? = null
    private var prepareJob: Job? = null

    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) {
                return -1
            }
            val safePosition = position.toInt()
            val bytesToRead = size.coerceAtMost(data.size - safePosition)
            if (bytesToRead <= 0) {
                return -1
            }
            System.arraycopy(data, safePosition, buffer, offset, bytesToRead)
            return bytesToRead
        }

        override fun close() {
        }
    }

    private val preparedListener = MediaPlayer.OnPreparedListener { mp ->
        isPrepared = true
        currentDurationMs = try {
            mp.duration.toLong()
        } catch (e: Exception) {
            0L
        }

        if (currentDurationMs > 0 && currentFileName != null) {
            _playbackState.value =
                RecordingPlaybackService.PlaybackState.Ready(currentFileName!!, currentDurationMs)
            _currentPositionMs.value = 0
        } else {
            handleError("MediaPlayer prepared with invalid duration or state")
        }
    }

    private val completionListener = MediaPlayer.OnCompletionListener {
        stopProgressPolling()
        _currentPositionMs.value = currentDurationMs
        if (currentFileName != null && currentDurationMs > 0) {
            _playbackState.value =
                RecordingPlaybackService.PlaybackState.Ready(currentFileName!!, currentDurationMs)
        } else {
            _playbackState.value = RecordingPlaybackService.PlaybackState.Idle
        }
        isPrepared = true
    }

    private val errorListener = MediaPlayer.OnErrorListener { _, what, extra ->
        handleError("MediaPlayer error (what=$what, extra=$extra)")
        true
    }

    override fun playRecording(fileName: String) {
        if (fileName == currentFileName && mediaPlayer != null && isPrepared) {
            resume()
            return
        }

        internalStop(resetState = false)
        prepareJob?.cancel()

        currentFileName = fileName
        _playbackState.value = RecordingPlaybackService.PlaybackState.Preparing(fileName)
        _currentPositionMs.value = 0L
        currentDurationMs = 0L

        prepareJob = serviceScope.launch {
            when (val result = secureMediaRepository.getDecryptedMediaData(fileName)) {
                is Result.Success -> {
                    val audioBytes = result.data
                    Log.d(
                        Constants.APP_TAG,
                        "Successfully decrypted ${audioBytes.size} bytes for $fileName"
                    )
                    if (fileName != currentFileName) {
                        Log.w(
                            Constants.APP_TAG,
                            "File changed during decryption, aborting preparation."
                        )
                        return@launch
                    }
                    prepareMediaPlayer(audioBytes)
                }

                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Failed to decrypt $fileName", result.exception)
                    withContext(Dispatchers.Main) {
                        handleError("Decryption failed for $fileName")
                    }
                }
            }
        }
    }

    private suspend fun prepareMediaPlayer(audioBytes: ByteArray) {
        withContext(Dispatchers.Main) {
            if (currentFileName == null) return@withContext
            Log.d(Constants.APP_TAG, "Preparing MediaPlayer on Main thread")
            try {
                isPrepared = false
                mediaPlayer = MediaPlayer().apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        setDataSource(ByteArrayMediaDataSource(audioBytes))
                    } else {
                        handleError("MediaDataSource requires API 23+")
                        return@withContext
                    }
                    setOnPreparedListener(preparedListener)
                    setOnCompletionListener(completionListener)
                    setOnErrorListener(errorListener)
                    prepareAsync()
                    Log.d(Constants.APP_TAG, "MediaPlayer.prepareAsync() called")
                }
            } catch (e: Exception) {
                Log.e(Constants.APP_TAG, "MediaPlayer setup failed", e)
                handleError("MediaPlayer setup failed: ${e.message}")
            }
        }
    }


    override fun pause() {
        if (mediaPlayer?.isPlaying == true && currentFileName != null) {
            Log.d(Constants.APP_TAG, "Pausing playback for $currentFileName")
            try {
                mediaPlayer?.pause()
                stopProgressPolling()
                _playbackState.value = RecordingPlaybackService.PlaybackState.Paused(
                    currentFileName!!,
                    currentDurationMs
                )
            } catch (e: IllegalStateException) {
                handleError("MediaPlayer pause failed: ${e.message}")
            }
        }
    }

    override fun resume() {
        if (mediaPlayer != null && isPrepared && !(mediaPlayer?.isPlaying
                ?: false) && currentFileName != null
        ) {
            Log.d(Constants.APP_TAG, "Resuming playback for $currentFileName")
            try {
                mediaPlayer?.start()
                startProgressPolling()
                _playbackState.value = RecordingPlaybackService.PlaybackState.Playing(
                    currentFileName!!,
                    currentDurationMs
                )
            } catch (e: IllegalStateException) {
                handleError("MediaPlayer resume (start) failed: ${e.message}")
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (mediaPlayer != null && isPrepared && currentFileName != null) {
            val safePosition = positionMs.coerceIn(0, currentDurationMs)
            Log.d(Constants.APP_TAG, "Seeking to $safePosition ms for $currentFileName")
            try {
                mediaPlayer?.seekTo(safePosition.toInt())
                _currentPositionMs.value = safePosition
                if (_playbackState.value is RecordingPlaybackService.PlaybackState.Ready && mediaPlayer?.isPlaying == false) {
                } else if (mediaPlayer?.isPlaying == false) {
                    _playbackState.value = RecordingPlaybackService.PlaybackState.Paused(
                        currentFileName!!,
                        currentDurationMs
                    )
                    stopProgressPolling()
                }
            } catch (e: IllegalStateException) {
                handleError("MediaPlayer seekTo failed: ${e.message}")
            }
        }
    }

    override fun stop() {
        Log.d(Constants.APP_TAG, "stop() called.")
        internalStop(resetState = true)
    }

    override fun release() {
        Log.d(Constants.APP_TAG, "release() called.")
        internalStop(resetState = true)
        serviceScope.cancel()
    }

    private fun internalStop(resetState: Boolean) {
        prepareJob?.cancel()
        prepareJob = null
        stopProgressPolling()
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                mediaPlayer?.reset()
                mediaPlayer?.release()
            } catch (e: Exception) {
                Log.w(Constants.APP_TAG, "Exception during MediaPlayer internalStop/release", e)
            } finally {
                mediaPlayer = null
            }
        }
        isPrepared = false
        currentDurationMs = 0L
        if (resetState) {
            currentFileName = null
            _playbackState.value = RecordingPlaybackService.PlaybackState.Idle
            _currentPositionMs.value = 0L
        }
    }

    private fun handleError(message: String) {
        Log.e(Constants.APP_TAG, "Playback Error: $message")
        val Cfn = currentFileName
        internalStop(resetState = false)
        _currentPositionMs.value = 0L
        _playbackState.value = RecordingPlaybackService.PlaybackState.Error(Cfn, message)
        currentFileName = null
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        if (mediaPlayer?.isPlaying == true) {
            progressPollingJob = serviceScope.launch {
                while (isActive) {
                    try {
                        if (mediaPlayer?.isPlaying == true && isPrepared) {
                            _currentPositionMs.value = mediaPlayer?.currentPosition?.toLong() ?: 0L
                        } else {
                            stopProgressPolling()
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(Constants.APP_TAG, "Error polling MediaPlayer position", e)
                        stopProgressPolling()
                    }
                    delay(500)
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }


}