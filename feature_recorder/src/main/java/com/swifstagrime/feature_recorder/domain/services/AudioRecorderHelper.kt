package com.swifstagrime.feature_recorder.domain.services

import kotlinx.coroutines.flow.Flow
import java.io.File

interface AudioRecorderHelper {
    sealed class RecorderEvent {
        data class Completed(val outputFile: File) : RecorderEvent()
        data class Error(val exception: Exception) : RecorderEvent()
    }

    val recordingEvents: Flow<RecorderEvent>

    suspend fun start(outputFile: File)

    suspend fun stop()

    fun release()

    fun getLastRecordingDuration(): Long

}