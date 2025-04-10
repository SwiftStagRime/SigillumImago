package com.swifstagrime.feature_doc_upload.ui.fragments.doc_upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.feature_doc_upload.domain.models.SelectedDocument
import com.swifstagrime.core_common.utils.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed interface UploadProgressState {
    object Idle : UploadProgressState
    data class Processing(
        val currentFileIndex: Int,
        val totalFiles: Int,
        val currentFileName: String,
        val overallProgressPercent: Int,
        val estimatedTimeRemainingMillis: Long?
    ) : UploadProgressState
}

sealed interface DocumentUploadUiState {
    object Idle : DocumentUploadUiState
    data class FilesSelected(val documents: List<SelectedDocument>) : DocumentUploadUiState
    data class Uploading(
        val documents: List<SelectedDocument>,
        val progress: UploadProgressState.Processing
    ) : DocumentUploadUiState
    data class Error(val message: String) : DocumentUploadUiState
}

sealed interface UiEvent {
    object RequestFileSelection : UiEvent
    object ShowUploadComplete : UiEvent
    data class ShowErrorSnackbar(val message: String) : UiEvent
}

@HiltViewModel
class DocumentUploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureMediaRepository: SecureMediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DocumentUploadUiState>(DocumentUploadUiState.Idle)
    val uiState: StateFlow<DocumentUploadUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private var uploadJob: Job? = null

    fun onAddFilesClicked() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.RequestFileSelection)
        }
    }

    fun onFilesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val currentDocs = (_uiState.value as? DocumentUploadUiState.FilesSelected)?.documents ?: emptyList()
            val newDocs = mutableListOf<SelectedDocument>()

            uris.forEach { uri ->
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                            val name = if (nameIndex != -1) cursor.getString(nameIndex) else "unknown_file_${System.currentTimeMillis()}"
                            val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else -1L

                            if (size != 0L) {
                                newDocs.add(SelectedDocument(uri = uri, fileName = name, sizeBytes = size))
                                Log.d(Constants.APP_TAG, "Processed URI: $uri, Name: $name, Size: $size")
                            } else {
                                Log.w(Constants.APP_TAG, "Skipping URI with size 0 or invalid: $uri")
                            }
                        } else {
                            Log.w(Constants.APP_TAG, "Could not moveToFirst for URI: $uri")
                        }
                    } ?: Log.w(Constants.APP_TAG, "ContentResolver query returned null for URI: $uri")
                } catch (e: Exception) {
                    Log.e(Constants.APP_TAG, "Error processing URI: $uri", e)
                }
            }

            if (newDocs.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = DocumentUploadUiState.FilesSelected(currentDocs + newDocs)
                }
            }
        }
    }

    fun onRemoveFileClicked(documentId: String) {
        val currentState = _uiState.value
        if (currentState is DocumentUploadUiState.FilesSelected) {
            val updatedList = currentState.documents.filterNot { it.id == documentId }
            _uiState.value = if (updatedList.isEmpty()) {
                DocumentUploadUiState.Idle
            } else {
                DocumentUploadUiState.FilesSelected(updatedList)
            }
        }
    }

    fun onUploadClicked() {
        val currentState = _uiState.value
        if (currentState !is DocumentUploadUiState.FilesSelected || currentState.documents.isEmpty()) {
            Log.w(Constants.APP_TAG, "Upload clicked but no files selected or wrong state.")
            return
        }
        if (uploadJob?.isActive == true) {
            Log.w(Constants.APP_TAG, "Upload already in progress.")
            return
        }

        val documentsToUpload = currentState.documents

        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            val totalFiles = documentsToUpload.size
            var filesProcessed = 0
            var bytesProcessed = 0L
            val startTime = System.currentTimeMillis()
            var firstFileFailed = false

            withContext(Dispatchers.Main.immediate){
                _uiState.value = DocumentUploadUiState.Uploading(
                    documentsToUpload,
                    UploadProgressState.Processing(0, totalFiles, documentsToUpload.firstOrNull()?.fileName ?: "", 0, null)
                )
            }

            for ((index, doc) in documentsToUpload.withIndex()) {
                if (!isActive) break

                val estimatedTime = calculateEstimatedTime(startTime, index, totalFiles)
                withContext(Dispatchers.Main.immediate) {
                    val uploadingState = _uiState.value
                    if (uploadingState is DocumentUploadUiState.Uploading) {
                        _uiState.value = uploadingState.copy(
                            progress = UploadProgressState.Processing(
                                currentFileIndex = index,
                                totalFiles = totalFiles,
                                currentFileName = doc.fileName,
                                overallProgressPercent = (index * 100) / totalFiles,
                                estimatedTimeRemainingMillis = estimatedTime
                            )
                        )
                    } else {
                        Log.w(Constants.APP_TAG, "Upload state changed during progress update, cancelling.")
                        this@launch.cancel()
                    }
                }

                Log.d(Constants.APP_TAG, "Uploading ${index + 1}/$totalFiles: ${doc.fileName}")

                val fileBytes: ByteArray? = try {
                    context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
                        inputStream.readBytes()
                    }
                } catch (e: IOException) {
                    Log.e(Constants.APP_TAG, "Failed to read file: ${doc.fileName}", e)
                    null
                } catch (e: SecurityException) {
                    Log.e(Constants.APP_TAG, "Permission denied for file: ${doc.fileName}", e)
                    null
                }

                if (fileBytes == null) {
                    Log.e(Constants.APP_TAG, "Stopping upload due to read error for ${doc.fileName}")
                    withContext(Dispatchers.Main.immediate){
                        _uiState.value = DocumentUploadUiState.Error("Failed to read file: ${doc.fileName}")
                        _uiEvents.emit(UiEvent.ShowErrorSnackbar("Error reading ${doc.fileName}"))
                    }
                    firstFileFailed = true
                    break
                }

                val saveResult = secureMediaRepository.saveMedia(
                    desiredFileName = doc.fileName,
                    mediaType = MediaType.DOCUMENT,
                    data = fileBytes
                )

                if (saveResult is Result.Error) {
                    Log.e(Constants.APP_TAG, "Failed to save file: ${doc.fileName}", saveResult.exception)
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.value = DocumentUploadUiState.Error("Failed to save file: ${doc.fileName}")
                        _uiEvents.emit(UiEvent.ShowErrorSnackbar("Error saving ${doc.fileName}"))
                    }
                    firstFileFailed = true
                    break
                } else {
                    Log.i(Constants.APP_TAG, "Successfully saved: ${doc.fileName}")
                    filesProcessed++
                    bytesProcessed += fileBytes.size
                }
            }

            if (isActive) {
                if (!firstFileFailed) {
                    Log.i(Constants.APP_TAG, "Upload process completed successfully.")
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.value = DocumentUploadUiState.Idle
                        _uiEvents.emit(UiEvent.ShowUploadComplete)
                    }
                } else {
                    Log.w(Constants.APP_TAG, "Upload process finished with an error.")
                }
            } else {
                Log.i(Constants.APP_TAG, "Upload process cancelled.")
            }
        }
    }

    private fun calculateEstimatedTime(startTimeMs: Long, processedCount: Int, totalCount: Int): Long? {
        if (processedCount <= 0 || totalCount <= processedCount) {
            return null
        }
        val elapsedTimeMs = System.currentTimeMillis() - startTimeMs
        val avgTimePerItemMs = elapsedTimeMs.toDouble() / processedCount
        val remainingItems = totalCount - processedCount
        val estimatedRemainingMs = (avgTimePerItemMs * remainingItems).toLong()
        return if (estimatedRemainingMs < 0) null else estimatedRemainingMs
    }

    override fun onCleared() {
        super.onCleared()
        uploadJob?.cancel()
    }


}