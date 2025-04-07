package com.swifstagrime.feature_gallery.ui.fragments.gallery

import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GalleryItem(
    val fileName: String,
    val mediaType: MediaType,
    val createdAtTimestampMillis: Long
)

sealed interface GalleryUiState {
    object Loading : GalleryUiState
    data class Error(val message: String) : GalleryUiState
    data class Success(val mediaItems: List<GalleryItem>) : GalleryUiState {
        val isEmpty: Boolean get() = mediaItems.isEmpty()
    }
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val secureMediaRepository: SecureMediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _isSelectionModeActive = MutableStateFlow(false)
    val isSelectionModeActive: StateFlow<Boolean> = _isSelectionModeActive.asStateFlow()

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val thumbnailCache = object : LruCache<String, Result<ByteArray>>(cacheSize) {
        override fun sizeOf(key: String, value: Result<ByteArray>): Int {
            return when (value) {
                is Result.Success -> (value.data.size / 1024).coerceAtLeast(1)
                is Result.Error -> 1
            }
        }
    }

    init {
        loadGalleryMedia()
    }

    private fun loadGalleryMedia() {
        viewModelScope.launch {
            secureMediaRepository.getAllMediaFiles()
                .onStart {
                    if (_uiState.value !is GalleryUiState.Success) {
                        _uiState.value = GalleryUiState.Loading
                    }
                }
                .map { mediaFiles ->
                    mediaFiles.map { file ->
                        GalleryItem(
                            fileName = file.fileName,
                            mediaType = file.mediaType,
                            createdAtTimestampMillis = file.createdAtTimestampMillis
                        )
                    }
                }
                .catch { exception ->
                    Log.e(Constants.APP_TAG, "Error loading gallery media", exception)
                    _uiState.value =
                        GalleryUiState.Error("Failed to load gallery: ${exception.localizedMessage}")
                }
                .collect { galleryItems ->
                    _selectedItems.update { currentSelection ->
                        currentSelection.intersect(galleryItems.map { it.fileName }.toSet())
                    }
                    if (_isSelectionModeActive.value && _selectedItems.value.isEmpty()) {
                        exitSelectionMode()
                    }
                    _uiState.value = GalleryUiState.Success(galleryItems)
                }
        }
    }

    suspend fun loadThumbnailData(fileName: String): Result<ByteArray> {
        val cachedResult = thumbnailCache.get(fileName)
        if (cachedResult != null) {
            return cachedResult
        }

        return withContext(Dispatchers.IO) {
            val result = secureMediaRepository.getDecryptedMediaData(fileName)
            if (coroutineContext.isActive) {
                thumbnailCache.put(fileName, result)
            }
            result
        }
    }

    fun clearThumbnailCache() {
        thumbnailCache.evictAll()
    }

    fun removeThumbnailFromCache(fileName: String) {
        thumbnailCache.remove(fileName)
    }

    fun enterSelectionMode(initialItemFileName: String) {
        if (!_isSelectionModeActive.value) {
            _isSelectionModeActive.value = true
            val currentItems =
                (_uiState.value as? GalleryUiState.Success)?.mediaItems?.map { it.fileName }
                    ?.toSet() ?: emptySet()
            if (currentItems.contains(initialItemFileName)) {
                _selectedItems.value = setOf(initialItemFileName)
            } else {
                _selectedItems.value = emptySet()
            }
        }
    }

    fun toggleSelection(fileName: String) {
        if (!_isSelectionModeActive.value) return

        _selectedItems.update { currentSelection ->
            if (currentSelection.contains(fileName)) {
                currentSelection - fileName
            } else {
                currentSelection + fileName
            }
        }

        if (_selectedItems.value.isEmpty()) {
            exitSelectionMode()
        }
    }

    fun exitSelectionMode() {
        if (_isSelectionModeActive.value) {
            _isSelectionModeActive.value = false
            _selectedItems.value = emptySet()
        }
    }

    fun deleteSelectedItems() {
        val itemsToDelete = _selectedItems.value
        if (itemsToDelete.isEmpty()) return

        exitSelectionMode()

        viewModelScope.launch(Dispatchers.IO) {
            var hasErrors = false
            itemsToDelete.forEach { fileName ->
                removeThumbnailFromCache(fileName)
                val deleteResult = secureMediaRepository.deleteMedia(fileName)
                if (deleteResult is Result.Error) {
                    hasErrors = true
                    Log.e(
                        Constants.APP_TAG,
                        "Failed to delete media item: $fileName",
                        deleteResult.exception
                    )
                }
            }

            if (hasErrors) {
                Log.w(Constants.APP_TAG, "One or more items failed to delete.")
            }
        }
    }

    override fun onCleared() {
        clearThumbnailCache()
        super.onCleared()
    }
}