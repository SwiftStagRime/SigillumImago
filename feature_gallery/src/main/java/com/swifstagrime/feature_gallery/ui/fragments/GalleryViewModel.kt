package com.swifstagrime.feature_gallery.ui.fragments

import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
                    _uiState.value = GalleryUiState.Error("Failed to load gallery: ${exception.localizedMessage}")
                }
                .collect { galleryItems ->
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

    override fun onCleared() {
        clearThumbnailCache()
        super.onCleared()
    }
}