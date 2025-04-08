package com.swifstagrime.feature_recorder.data.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.swifstagrime.feature_recorder.domain.services.AudioRecorderHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
class AudioRecorderHelperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioRecorderHelper {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0L
    private var lastDurationMillis: Long = 0L

    private val _recordingEvents = MutableSharedFlow<AudioRecorderHelper.RecorderEvent>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val recordingEvents: SharedFlow<AudioRecorderHelper.RecorderEvent> =
        _recordingEvents.asSharedFlow()

    private val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun start(outputFile: File) {
        if (recorder != null) {
            throw IllegalStateException("Recorder is already active.")
        }
        currentOutputFile = outputFile
        startTimeMillis = 0L
        lastDurationMillis = 0L

        withContext(Dispatchers.IO) {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                newRecorder.setOutputFile(outputFile.absolutePath)

                newRecorder.setOnErrorListener { _, what, extra ->
                    val exception = IOException("MediaRecorder error (what=$what, extra=$extra)")
                    _recordingEvents.tryEmit(AudioRecorderHelper.RecorderEvent.Error(exception))
                    releaseInternal()
                }

                newRecorder.prepare()
                startTimeMillis = System.currentTimeMillis()
                newRecorder.start()
                recorder = newRecorder
            } catch (e: Exception) {
                newRecorder.release()
                recorder = null
                currentOutputFile = null
                throw e
            }
        }
    }

    override suspend fun stop() {
        val recorderToStop =
            recorder ?: throw IllegalStateException("Recorder is not currently recording.")

        withContext(Dispatchers.IO) {
            try {
                Log.d("AudioRecorderHelper", "Attempting to stop MediaRecorder...")
                recorderToStop.stop()
                Log.d("AudioRecorderHelper", "MediaRecorder stopped.")
                recorderToStop.reset()
                lastDurationMillis =
                    if (startTimeMillis > 0) System.currentTimeMillis() - startTimeMillis else 0L
                val file = currentOutputFile
                if (file != null) {
                    Log.d(
                        "AudioRecorderHelper",
                        "Recording stopped. File: ${file.absolutePath}, Duration: $lastDurationMillis ms"
                    )
                    helperScope.launch {
                        _recordingEvents.emit(
                            AudioRecorderHelper.RecorderEvent.Completed(
                                file
                            )
                        )
                    }
                } else {
                    Log.e("AudioRecorderHelper", "Output file was null after stopping.")
                    helperScope.launch {
                        _recordingEvents.emit(
                            AudioRecorderHelper.RecorderEvent.Error(
                                IOException("Output file was null after stopping.")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioRecorderHelper", "MediaRecorder stop failed", e)
                helperScope.launch { _recordingEvents.emit(AudioRecorderHelper.RecorderEvent.Error(e)) }
                lastDurationMillis = 0L
            } finally {
                recorder = null
                currentOutputFile = null
                startTimeMillis = 0L
            }
        }
    }

    override fun release() {
        releaseInternal()
        helperScope.cancel()
    }

    private fun releaseInternal() {
        if (recorder != null) {
            try {
                recorder?.reset()
                recorder?.release()
            } catch (e: Exception) {
            } finally {
                recorder = null
                currentOutputFile = null
                startTimeMillis = 0L
            }
        }
    }


    override fun getLastRecordingDuration(): Long {
        return lastDurationMillis
    }
}