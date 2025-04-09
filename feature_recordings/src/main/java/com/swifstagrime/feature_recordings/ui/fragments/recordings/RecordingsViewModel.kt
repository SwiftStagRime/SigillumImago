package com.swifstagrime.feature_recordings.ui.fragments.recordings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.feature_recordings.domain.models.RecordingDisplayInfo
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface RecordingsListState {
    object Loading : RecordingsListState
    data class Success(val recordings: List<RecordingDisplayInfo>) : RecordingsListState
    data class Error(val message: String) : RecordingsListState
}

sealed interface UiEvent {
    data class ShowDeleteConfirmation(val fileName: String) : UiEvent
    data class ShowError(val message: String) : UiEvent
}

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val secureMediaRepository: SecureMediaRepository,
    private val playbackService: RecordingPlaybackService
) : ViewModel() {

    private val _listState = MutableStateFlow<RecordingsListState>(RecordingsListState.Loading)
    val listState: StateFlow<RecordingsListState> = _listState.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playbackService.playbackState

    val currentPositionMs: StateFlow<Long> = playbackService.currentPositionMs

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private var deleteJob: Job? = null

    init {
        loadRecordings()
    }

    private fun loadRecordings() {
        viewModelScope.launch {
            _listState.value = RecordingsListState.Loading
            secureMediaRepository.getAllMediaFiles()
                .map { mediaFiles ->
                    mediaFiles.filter { it.mediaType == MediaType.AUDIO }
                }
                .flatMapLatest { audioFiles ->
                    fetchDisplayNamesAndCombine(audioFiles)
                }
                .catch { e ->
                    Log.e(Constants.APP_TAG, "Error in recordings flow", e)
                    withContext(Dispatchers.Main.immediate) {
                        _listState.value =
                            RecordingsListState.Error("Failed to load recordings: ${e.message}")
                    }
                    emit(emptyList())
                }
                .flowOn(Dispatchers.Default)
                .collectLatest { displayInfos ->
                    withContext(Dispatchers.Main.immediate) {
                        Log.d(
                            Constants.APP_TAG,
                            "Updating list state with ${displayInfos.size} items."
                        )
                        _listState.value = RecordingsListState.Success(displayInfos)
                    }
                }
        }
    }

    private suspend fun fetchDisplayNamesAndCombine(
        audioFiles: List<MediaFile>
    ): Flow<List<RecordingDisplayInfo>> = flow {

        if (audioFiles.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val displayInfos = coroutineScope {
            audioFiles.map { mediaFile ->
                async(Dispatchers.IO) {
                    val nameResult =
                        secureMediaRepository.getDecryptedDisplayName(mediaFile.fileName)
                    val displayName = when (nameResult) {
                        is Result.Success -> nameResult.data ?: mediaFile.fileName
                        is Result.Error -> {
                            Log.e(
                                Constants.APP_TAG,
                                "Failed to get display name for ${mediaFile.fileName}",
                                nameResult.exception
                            )
                            mediaFile.fileName
                        }
                    }
                    RecordingDisplayInfo(mediaFile, displayName)
                }
            }.awaitAll()
        }
        emit(displayInfos)
    }

    fun onRecordingClicked(fileName: String) {
        val currentState = playbackService.playbackState.value
        Log.d(Constants.APP_TAG, "onRecordingClicked: $fileName, Current State: $currentState")

        when (val currentState = playbackService.playbackState.value) {
            is RecordingPlaybackService.PlaybackState.Playing -> {
                if (currentState.fileName == fileName) {
                    playbackService.pause()
                } else {
                    playbackService.playRecording(fileName)
                }
            }

            is RecordingPlaybackService.PlaybackState.Paused -> {
                if (currentState.fileName == fileName) {
                    playbackService.resume()
                } else {
                    playbackService.playRecording(fileName)
                }
            }

            is RecordingPlaybackService.PlaybackState.Ready -> {
                if (currentState.fileName == fileName) {
                    playbackService.resume()
                } else {
                    playbackService.playRecording(fileName)
                }
            }

            is RecordingPlaybackService.PlaybackState.Idle,
            is RecordingPlaybackService.PlaybackState.Error,
            is RecordingPlaybackService.PlaybackState.Preparing -> {
                playbackService.playRecording(fileName)
            }
        }
    }

    fun onSeekBarSeeked(positionMs: Long) {
        playbackService.seekTo(positionMs)
    }

    fun onDeleteClicked(fileName: String) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowDeleteConfirmation(fileName))
        }
    }

    fun onDeleteConfirmed(fileName: String) {
        if (deleteJob?.isActive == true) {
            Log.w(Constants.APP_TAG, "Deletion already in progress.")
            return
        }

        deleteJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d(Constants.APP_TAG, "Deleting recording: $fileName")

            val currentPlaybackFile = when (val state = playbackService.playbackState.value) {
                is PlaybackState.Preparing -> state.fileName
                is PlaybackState.Ready -> state.fileName
                is PlaybackState.Playing -> state.fileName
                is PlaybackState.Paused -> state.fileName
                else -> null
            }
            if (currentPlaybackFile == fileName) {
                withContext(Dispatchers.Main) {
                    playbackService.stop()
                }
            }

            val result = secureMediaRepository.deleteMedia(fileName)

            when (result) {
                is Result.Success -> {
                    Log.i(Constants.APP_TAG, "Successfully deleted: $fileName")
                }

                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Failed to delete $fileName", result.exception)
                    withContext(Dispatchers.Main) {
                        _uiEvents.emit(UiEvent.ShowError("Failed to delete $fileName: ${result.exception.message}"))
                    }
                }
            }
        }
    }

    fun stopPlayback() {
        playbackService.stop()
    }

    override fun onCleared() {
        Log.d(Constants.APP_TAG, "RecordingsViewModel cleared.")
        super.onCleared()
        playbackService.release()
        deleteJob?.cancel()
    }


}