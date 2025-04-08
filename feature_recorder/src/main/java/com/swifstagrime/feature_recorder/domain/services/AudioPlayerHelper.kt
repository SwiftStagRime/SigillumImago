package com.swifstagrime.feature_recorder.domain.services

import kotlinx.coroutines.flow.Flow
import java.io.File

interface AudioPlayerHelper {
    sealed class PlayerEvent {
        data class Prepared(val durationMs: Long) : PlayerEvent()
        object Completion : PlayerEvent()
        data class Error(val exception: Exception) : PlayerEvent()
        data class Progress(val positionMs: Long) : PlayerEvent()
    }

    val playbackEvents: Flow<PlayerEvent>

    suspend fun prepare(inputFile: File)

    fun play()

    fun pause()

    fun release()

    fun seekTo(positionMs: Long)

    fun getCurrentPosition(): Long

    fun providesProgressUpdates(): Boolean
}