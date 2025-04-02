package com.swifstagrime.feature_camera.ui.fragments.camera

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class FlashMode(val imageCaptureMode: Int) {
    ON(ImageCapture.FLASH_MODE_ON),
    OFF(ImageCapture.FLASH_MODE_OFF),
    AUTO(ImageCapture.FLASH_MODE_AUTO)
}

enum class LensFacing(val cameraSelectorConstant: Int) {
    BACK(CameraSelector.LENS_FACING_BACK),
    FRONT(CameraSelector.LENS_FACING_FRONT)
}

sealed class CameraInitState {
    object Idle : CameraInitState()
    object NeedsPermission : CameraInitState()
    object PermissionDenied : CameraInitState()
    object Initializing : CameraInitState()
    object Ready : CameraInitState()
    data class Error(val message: String, val cause: Throwable? = null) : CameraInitState()
}

sealed class CaptureState {
    object Idle : CaptureState()
    object Capturing : CaptureState()
    data class Saving(val estimatedFileName: String) : CaptureState()
    data class Success(val savedMediaFile: MediaFile) : CaptureState()
    data class Error(val message: String, val cause: Throwable? = null) : CaptureState()
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val secureMediaRepository: SecureMediaRepository,
) : ViewModel() {

    private val _cameraInitState = MutableStateFlow<CameraInitState>(CameraInitState.Idle)
    val cameraInitState: StateFlow<CameraInitState> = _cameraInitState.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(LensFacing.BACK)
    val lensFacing: StateFlow<LensFacing> = _lensFacing.asStateFlow()

    /** To be called by Fragment when it checks permissions */
    fun onPermissionCheckResult(hasCameraPermission: Boolean, hasStoragePermission: Boolean = true) {
        // Note: WRITE_EXTERNAL_STORAGE might not be strictly needed for saving to app-specific
        // directory post Android Q, but good practice to check if targeting older OS or
        // if future features might need it. Let's assume needed for now for robustness.
        // For API 23-29, storage permission IS needed. For API 30+, maybe not for app-specific dir.
        // Simplifying: Require camera perm. Storage perm handled by repository if needed.
        // UPDATE: For saving to app's internal storage (context.filesDir), no storage permission needed.

        if (hasCameraPermission) {
            _cameraInitState.value = CameraInitState.Initializing
        } else {
            _cameraInitState.value = CameraInitState.PermissionDenied
        }
    }

    fun onCameraReady() {
        if (_cameraInitState.value == CameraInitState.Initializing) {
            _cameraInitState.value = CameraInitState.Ready
        }
    }

    fun onCameraSetupError(exception: Exception) {
        _cameraInitState.value = CameraInitState.Error("Failed to initialize camera", exception)
    }

    fun requestPermissions() {
        _cameraInitState.value = CameraInitState.NeedsPermission
    }

    fun onTakePhotoClicked() {
        if (_cameraInitState.value == CameraInitState.Ready && _captureState.value == CaptureState.Idle) {
            _captureState.value = CaptureState.Capturing
        } else {
            Log.w(Constants.APP_TAG, "Capture requested but state is not ready or idle.")
        }
    }

    fun onPhotoCaptured(imageData: ByteArray) {
        val timestamp = System.currentTimeMillis()
        val simpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val dateString = simpleDateFormat.format(Date(timestamp))
        val desiredFileName = "IMG_${dateString}.jpg"

        _captureState.value = CaptureState.Saving(desiredFileName)

        viewModelScope.launch(Dispatchers.IO) {
            val saveResult = secureMediaRepository.saveMedia(
                desiredFileName = desiredFileName,
                mediaType = MediaType.PHOTO,
                data = imageData
            )

            when (saveResult) {
                is Result.Success -> {
                    _captureState.value = CaptureState.Success(saveResult.data)
                }
                is Result.Error -> {
                    _captureState.value = CaptureState.Error(
                        message = "Failed to save photo: ${saveResult.exception.message}",
                        cause = saveResult.exception
                    )
                }
            }
        }
    }

    fun onPhotoCaptureError(exception: Exception) {
        _captureState.value = CaptureState.Error("Photo capture failed", exception)
    }

    fun onSwitchCameraClicked() {
        if (_cameraInitState.value is CameraInitState.Initializing || _cameraInitState.value is CameraInitState.Ready) {
            _lensFacing.update { currentFacing ->
                if (currentFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
            }
            _cameraInitState.value = CameraInitState.Initializing
        }
    }

    fun onFlashButtonClicked() {
        _flashMode.update { currentMode ->
            when (currentMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
        }
    }

    fun resetCaptureState() {
        if (_captureState.value is CaptureState.Success || _captureState.value is CaptureState.Error) {
            _captureState.value = CaptureState.Idle
        }
    }

    fun checkInitialState() {
        if (_cameraInitState.value == CameraInitState.Idle) {
            _cameraInitState.value = CameraInitState.NeedsPermission
        }
    }
}