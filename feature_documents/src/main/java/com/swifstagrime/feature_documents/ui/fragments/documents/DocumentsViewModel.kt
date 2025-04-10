package com.swifstagrime.feature_documents.ui.fragments.documents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.feature_documents.domain.models.DocumentDisplayInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

sealed interface DocumentsListState {
    object Loading : DocumentsListState
    data class Success(val documents: List<DocumentDisplayInfo>) : DocumentsListState
    data class Error(val message: String) : DocumentsListState
}

sealed interface UiEvent {
    data class ShowDeleteConfirmation(val internalFileName: String, val displayName: String) : UiEvent
    data class PrepareToOpenFile(
        val decryptedData: ByteArray,
        val originalDisplayName: String
    ) : UiEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PrepareToOpenFile
            if (!decryptedData.contentEquals(other.decryptedData)) return false
            if (originalDisplayName != other.originalDisplayName) return false
            return true
        }

        override fun hashCode(): Int {
            var result = decryptedData.contentHashCode()
            result = 31 * result + originalDisplayName.hashCode()
            return result
        }
    }

    data class ShowError(val message: String) : UiEvent
    object ShowLoadingIndicator : UiEvent
    object HideLoadingIndicator : UiEvent


}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val secureMediaRepository: SecureMediaRepository,
) : ViewModel() {

    private val _listState = MutableStateFlow<DocumentsListState>(DocumentsListState.Loading)
    val listState: StateFlow<DocumentsListState> = _listState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private var deleteJob: Job? = null
    private var openFileJob: Job? = null

    init {
        loadDocuments()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadDocuments() {
        viewModelScope.launch {
            _listState.value = DocumentsListState.Loading
            secureMediaRepository.getAllMediaFiles()
                .map { mediaFiles ->
                    mediaFiles.filter { it.mediaType == MediaType.DOCUMENT }
                }
                .flatMapLatest { documentFiles ->
                    fetchDisplayNamesAndCombine(documentFiles)
                }
                .catch { e ->
                    Log.e(Constants.APP_TAG, "Error in documents flow", e)
                    withContext(Dispatchers.Main.immediate) {
                        _listState.value = DocumentsListState.Error("Failed to load documents: ${e.message}")
                    }
                    emit(emptyList())
                }
                .flowOn(Dispatchers.Default)
                .collectLatest { displayInfos ->
                    withContext(Dispatchers.Main.immediate) {
                        Log.d(Constants.APP_TAG, "Updating document list state with ${displayInfos.size} items.")
                        _listState.value = DocumentsListState.Success(displayInfos)
                    }
                }
        }
    }

    private fun fetchDisplayNamesAndCombine(
        documentFiles: List<MediaFile>
    ): Flow<List<DocumentDisplayInfo>> = flow {
        if (documentFiles.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val displayInfos = coroutineScope {
            documentFiles.map { mediaFile ->
                async(Dispatchers.IO) {
                    val nameResult = secureMediaRepository.getDecryptedDisplayName(mediaFile.fileName)
                    val displayName = when (nameResult) {
                        is Result.Success -> nameResult.data ?: mediaFile.fileName
                        is Result.Error -> {
                            Log.e(Constants.APP_TAG, "Failed get display name for ${mediaFile.fileName}", nameResult.exception)
                            mediaFile.fileName
                        }
                    }
                    DocumentDisplayInfo(mediaFile, displayName)
                }
            }.awaitAll()
        }
        emit(displayInfos)
    }

    fun onDocumentClicked(docInfo: DocumentDisplayInfo) {
        if (openFileJob?.isActive == true) {
            Log.w(Constants.APP_TAG, "File opening already in progress.")
            return
        }
        openFileJob = viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowLoadingIndicator)
            Log.d(Constants.APP_TAG, "Attempting to decrypt document: ${docInfo.internalFileName}")

            val decryptResult = withContext(Dispatchers.IO) {
                secureMediaRepository.getDecryptedMediaData(docInfo.internalFileName)
            }

            when (decryptResult) {
                is Result.Success -> {
                    Log.d(Constants.APP_TAG, "Successfully decrypted ${docInfo.displayName}")
                    _uiEvents.emit(
                        UiEvent.PrepareToOpenFile(
                            decryptedData = decryptResult.data,
                            originalDisplayName = docInfo.displayName
                        )
                    )
                }
                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Failed to decrypt document: ${docInfo.internalFileName}", decryptResult.exception)
                    _uiEvents.emit(UiEvent.ShowError("Failed to open '${docInfo.displayName}': Decryption error."))
                }
            }
            _uiEvents.emit(UiEvent.HideLoadingIndicator)
        }
    }

    fun onDeleteClicked(docInfo: DocumentDisplayInfo) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowDeleteConfirmation(docInfo.internalFileName, docInfo.displayName))
        }
    }

    fun onDeleteConfirmed(internalFileName: String) {
        if (deleteJob?.isActive == true) return

        deleteJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d(Constants.APP_TAG, "Deleting document: $internalFileName")
            val result = secureMediaRepository.deleteMedia(internalFileName)

            when (result) {
                is Result.Success -> {
                    Log.i(Constants.APP_TAG, "Successfully deleted document: $internalFileName")
                }
                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "Failed to delete document: $internalFileName", result.exception)
                    withContext(Dispatchers.Main.immediate) {
                        _uiEvents.emit(UiEvent.ShowError("Failed to delete document: ${result.exception.message}"))
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        deleteJob?.cancel()
        openFileJob?.cancel()
    }
}