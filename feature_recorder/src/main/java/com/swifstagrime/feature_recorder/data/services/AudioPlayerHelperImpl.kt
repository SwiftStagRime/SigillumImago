package com.swifstagrime.feature_recorder.data.services

import android.media.MediaPlayer
import android.util.Log
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.feature_recorder.domain.services.AudioPlayerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerHelperImpl @Inject constructor() : AudioPlayerHelper {

    private var player: MediaPlayer? = null
    private var currentDataSource: String? = null
    private var isPrepared = false
    private var progressJob: Job? = null

    private val _playbackEvents = MutableSharedFlow<AudioPlayerHelper.PlayerEvent>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val playbackEvents: SharedFlow<AudioPlayerHelper.PlayerEvent> =
        _playbackEvents.asSharedFlow()

    private val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val preparedListener = MediaPlayer.OnPreparedListener { mp ->
        isPrepared = true
        val duration = try {
            mp.duration
        } catch (e: Exception) {
            -1
        }
        Log.d("AudioPlayerHelper", "onPrepared called. Duration: $duration")
        if (duration > 0) {
            helperScope.launch {
                _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Prepared(duration.toLong()))
            }
        } else {
            Log.e(
                "AudioPlayerHelper",
                "MediaPlayer reported invalid duration: $duration for ${currentDataSource ?: "Unknown Source"}"
            )
            helperScope.launch {
                _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Error(IOException("Invalid media duration reported: $duration")))
            }
        }
    }

    private val completionListener = MediaPlayer.OnCompletionListener { mp ->
        stopProgressUpdates()
        helperScope.launch {
            _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Completion)
        }
    }

    private val errorListener = MediaPlayer.OnErrorListener { mp, what, extra ->
        val exception =
            IOException("MediaPlayer error (what=$what, extra=$extra) for ${currentDataSource ?: "Unknown Source"}")
        Log.e("AudioPlayerHelper", "MediaPlayer error occurred.", exception)
        isPrepared = false
        stopProgressUpdates()
        helperScope.launch {
            _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Error(exception))
        }
        releaseInternal()
        true
    }

    override suspend fun prepare(inputFile: File) {
        releaseInternal()
        if (!inputFile.exists()) {
            throw IOException("Input file does not exist: ${inputFile.path}")
        }

        withContext(Dispatchers.IO) {
            try {
                Log.d(
                    "AudioPlayerHelper",
                    "Preparing MediaPlayer for file: ${inputFile.absolutePath}"
                )
                player = MediaPlayer().apply {
                    setDataSource(inputFile.absolutePath)
                    currentDataSource = inputFile.absolutePath
                    setOnPreparedListener(preparedListener)
                    setOnCompletionListener(completionListener)
                    setOnErrorListener(errorListener)
                    Log.d("AudioPlayerHelper", "Calling prepareAsync()")
                    prepareAsync()
                }
                isPrepared = false
            } catch (e: Exception) {
                Log.e(Constants.APP_TAG, "MediaPlayer prepare failed", e)
                releaseInternal()
                throw e
            }
        }
    }

    override fun play() {
        val playerInstance = player ?: throw IllegalStateException("Player is not initialized.")
        if (!isPrepared) throw IllegalStateException("Player is not prepared.")

        try {
            Log.d("AudioPlayerHelper", "Calling playerInstance.start()")
            playerInstance.start()
            startProgressUpdates()
        } catch (e: IllegalStateException) {
            Log.e("AudioPlayerHelper", "MediaPlayer start failed", e)
            helperScope.launch { _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Error(e)) }
        }
    }

    override fun pause() {
        val playerInstance = player ?: throw IllegalStateException("Player is not initialized.")
        if (!isPrepared || !playerInstance.isPlaying) {
            Log.w("AudioPlayerHelper", "Pause called when not playing or not prepared.")
            return
        }
        try {
            playerInstance.pause()
            stopProgressUpdates()
        } catch (e: IllegalStateException) {
            Log.e(Constants.APP_TAG, "MediaPlayer pause failed", e)
            helperScope.launch { _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Error(e)) }
        }
    }

    override fun seekTo(positionMs: Long) {
        val playerInstance = player ?: throw IllegalStateException("Player is not initialized.")
        if (!isPrepared) throw IllegalStateException("Player is not prepared for seek.")

        try {
            val safePosition = positionMs.coerceAtLeast(0)

            Log.d("AudioPlayerHelper", "Seeking to $safePosition ms")
            playerInstance.seekTo(safePosition.toInt())

        } catch (e: IllegalStateException) {
            Log.e(Constants.APP_TAG, "MediaPlayer seekTo failed", e)
            helperScope.launch { _playbackEvents.emit(AudioPlayerHelper.PlayerEvent.Error(e)) }
        }
    }

    override fun release() {
        releaseInternal()
        helperScope.cancel()
    }

    private fun releaseInternal() {
        stopProgressUpdates()
        if (player != null) {
            try {
                player?.stop()
                player?.reset()
                player?.release()
            } catch (e: Exception) {
                Log.w(Constants.APP_TAG, "Exception during MediaPlayer release", e)
            } finally {
                player = null
                isPrepared = false
                currentDataSource = null
            }
        }
    }

    override fun getCurrentPosition(): Long {
        return if (player != null && isPrepared) {
            try {
                player?.currentPosition?.toLong() ?: 0L
            } catch (e: IllegalStateException) {
                0L
            }
        } else {
            0L
        }
    }

    override fun providesProgressUpdates(): Boolean = false

    private fun startProgressUpdates() {
        stopProgressUpdates()
        if (player != null && isPrepared) {
            progressJob = helperScope.launch {
                while (true) {
                    try {
                        if (player?.isPlaying == true && isPrepared) {
                            val currentPos = player?.currentPosition?.toLong() ?: 0L
                        } else {
                            stopProgressUpdates()
                            break
                        }
                    } catch (e: IllegalStateException) {
                        stopProgressUpdates()
                        break
                    }
                    delay(500)
                }
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }


}