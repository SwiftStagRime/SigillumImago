package com.swifstagrime.feature_gallery.ui.fragments.photo_detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PhotoLoadState {
    object Loading : PhotoLoadState()
    data class Success(val bitmap: Bitmap) : PhotoLoadState()
    data class Error(val message: String) : PhotoLoadState()
}

sealed class DeleteState {
    object Idle : DeleteState()
    object Deleting : DeleteState()
    object Deleted : DeleteState()
    data class Error(val message: String) : DeleteState()
}


@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val secureMediaRepository: SecureMediaRepository,
) : ViewModel() {

    private val _photoLoadState = MutableStateFlow<PhotoLoadState>(PhotoLoadState.Loading)
    val photoLoadState: StateFlow<PhotoLoadState> = _photoLoadState.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    val fileName: StateFlow<String?> = savedStateHandle.getStateFlow("fileName", null)

    fun loadPhoto() {
        val currentFileName = fileName.value ?: return

        if (_photoLoadState.value is PhotoLoadState.Success) return

        _photoLoadState.value = PhotoLoadState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = secureMediaRepository.getDecryptedMediaData(currentFileName)) {
                is Result.Success -> {
                    try {
                        val options = BitmapFactory.Options().apply {
                            inMutable = true
                        }
                        val bitmap =
                            BitmapFactory.decodeByteArray(result.data, 0, result.data.size, options)
                        if (bitmap != null) {
                            _photoLoadState.value = PhotoLoadState.Success(bitmap)
                        } else {
                            _photoLoadState.value =
                                PhotoLoadState.Error("Failed to decode image data.")
                        }
                    } catch (e: OutOfMemoryError) {
                        _photoLoadState.value =
                            PhotoLoadState.Error("Image too large to display (Out of Memory).")
                    } catch (e: Exception) {
                        _photoLoadState.value =
                            PhotoLoadState.Error("Failed to decode image: ${e.message}")
                    }
                }

                is Result.Error -> {
                    _photoLoadState.value =
                        PhotoLoadState.Error("Load failed: ${result.exception.message}")
                }
            }
        }
    }

    fun deletePhoto() {
        val currentFileName = fileName.value ?: return

        _deleteState.value = DeleteState.Deleting
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = secureMediaRepository.deleteMedia(currentFileName)) {
                is Result.Success -> {
                    _deleteState.value = DeleteState.Deleted
                }

                is Result.Error -> {
                    _deleteState.value =
                        DeleteState.Error("Delete failed: ${result.exception.message}")
                }
            }
        }
    }

    fun resetDeleteState() {
        if (_deleteState.value is DeleteState.Error) {
            _deleteState.value = DeleteState.Idle
        }
    }
}