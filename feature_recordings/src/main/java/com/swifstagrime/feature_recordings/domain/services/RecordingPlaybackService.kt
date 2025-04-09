package com.swifstagrime.feature_recordings.domain.services

import kotlinx.coroutines.flow.StateFlow

interface RecordingPlaybackService {

    sealed interface PlaybackState {
        object Idle : PlaybackState
        data class Preparing(val fileName: String) : PlaybackState
        data class Ready(val fileName: String, val durationMs: Long) : PlaybackState
        data class Playing(val fileName: String, val durationMs: Long) : PlaybackState
        data class Paused(val fileName: String, val durationMs: Long) : PlaybackState
        data class Error(val fileName: String?, val message: String) : PlaybackState
    }

    val playbackState: StateFlow<PlaybackState>

    val currentPositionMs: StateFlow<Long>

    fun playRecording(fileName: String)

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun stop()

    fun release()


}