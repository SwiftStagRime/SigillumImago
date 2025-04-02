package com.swifstagrime.feature_camera.ui.fragments.camera

import android.content.pm.PackageManager
import androidx.fragment.app.viewModels
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_camera.R
import com.swifstagrime.feature_camera.databinding.FragmentCameraBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.Manifest

@AndroidEntryPoint
class CameraFragment : BaseFragment<FragmentCameraBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentCameraBinding
        get() = FragmentCameraBinding::inflate

    private val viewModel: CameraViewModel by viewModels()

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var currentFlashMode: FlashMode = FlashMode.OFF

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            viewModel.onPermissionCheckResult(isGranted)
        }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cameraExecutor = Executors.newSingleThreadExecutor()
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkInitialState()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }


    override fun setupViews() {
        binding.captureButton.setOnClickListener {
            viewModel.onTakePhotoClicked()
        }
        binding.switchCameraButton.setOnClickListener {
            viewModel.onSwitchCameraClicked()
        }
        binding.flashButton.setOnClickListener {
            viewModel.onFlashButtonClicked()
        }
        binding.galleryButton.setOnClickListener {
            showSnackbar("Gallery feature not yet implemented.")
        }
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cameraInitState.collect { state ->
                        Log.d(Constants.APP_TAG, "CameraInitState changed: $state")
                        when (state) {
                            CameraInitState.Idle -> { /* Initial state */ }
                            CameraInitState.NeedsPermission -> requestCameraPermission()
                            CameraInitState.PermissionDenied -> showPermissionDeniedUI()
                            CameraInitState.Initializing -> startCamera()
                            CameraInitState.Ready -> showCameraReadyUI()
                            is CameraInitState.Error -> showCameraErrorUI(state.message)
                        }
                    }
                }

                launch {
                    viewModel.captureState.collect { state ->
                        Log.d(Constants.APP_TAG, "CaptureState changed: $state")
                        updateUIForCaptureState(state)
                        if (state == CaptureState.Capturing) {
                            takePhoto()
                        }
                    }
                }

                launch {
                    viewModel.flashMode.collect { mode ->
                        Log.d(Constants.APP_TAG, "FlashMode changed: $mode")
                        currentFlashMode = mode
                        updateFlashButtonIcon(mode)
                        imageCapture?.flashMode = mode.imageCaptureMode
                    }
                }

                launch {
                    viewModel.lensFacing.collect { facing ->
                        Log.d(Constants.APP_TAG, "LensFacing changed: $facing")
                        if (currentLensFacing != facing) {
                            currentLensFacing = facing
                            if(viewModel.cameraInitState.value == CameraInitState.Initializing ||
                                viewModel.cameraInitState.value == CameraInitState.Ready) {
                                startCamera()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing.cameraSelectorConstant)
                .build()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder()
                .setFlashMode(currentFlashMode.imageCaptureMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                Log.d(Constants.APP_TAG, "CameraX Use cases bound successfully.")
                viewModel.onCameraReady()

            } catch (exc: Exception) {
                Log.e(Constants.APP_TAG, "Use case binding failed", exc)
                viewModel.onCameraSetupError(exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(Constants.APP_TAG, "Photo capture succeeded.")
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    viewModel.onPhotoCaptured(bytes)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(Constants.APP_TAG, "Photo capture failed: ${exc.message}", exc)
                    viewModel.onPhotoCaptureError(exc)
                }
            }
        )
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(Constants.APP_TAG, "Camera permission already granted.")
                viewModel.onPermissionCheckResult(true)
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(Constants.APP_TAG, "Showing camera permission rationale.")
                showSnackbarWithAction(
                    getString(com.swifstagrime.core_ui.R.string.camera_permission_rationale),
                    getString(com.swifstagrime.core_ui.R.string.grant)
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            else -> {
                Log.d(Constants.APP_TAG, "Requesting camera permission.")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showPermissionDeniedUI() {
        binding.statusTextView.text = getString(com.swifstagrime.core_ui.R.string.camera_permission_denied_message)
        binding.statusTextView.visibility = View.VISIBLE
        binding.captureButton.isEnabled = false
        binding.switchCameraButton.isEnabled = false
        binding.flashButton.isEnabled = false
    }

    private fun showCameraReadyUI() {
        binding.statusTextView.visibility = View.GONE
        binding.captureButton.isEnabled = true
        binding.switchCameraButton.isEnabled = true
        binding.flashButton.isEnabled = true
        updateFlashButtonIcon(viewModel.flashMode.value)
    }

    private fun showCameraErrorUI(message: String?) {
        binding.statusTextView.text = getString(com.swifstagrime.core_ui.R.string.camera_error_message, message ?: "Unknown error")
        binding.statusTextView.visibility = View.VISIBLE
        binding.captureButton.isEnabled = false
        binding.switchCameraButton.isEnabled = false
        binding.flashButton.isEnabled = false
    }

    private fun updateUIForCaptureState(state: CaptureState) {
        val isBusy = state is CaptureState.Capturing || state is CaptureState.Saving
        binding.captureButton.isEnabled = !isBusy
        binding.switchCameraButton.isEnabled = !isBusy
        binding.flashButton.isEnabled = !isBusy
        binding.galleryButton.isEnabled = !isBusy

        when (state) {
            is CaptureState.Saving -> {
                binding.statusTextView.text = getString(com.swifstagrime.core_ui.R.string.saving_photo)
                binding.statusTextView.visibility = View.VISIBLE
            }
            is CaptureState.Success -> {
                binding.statusTextView.visibility = View.GONE
                showSnackbar("Photo saved: ${state.savedMediaFile.fileName}")
                viewModel.resetCaptureState()
            }
            is CaptureState.Error -> {
                binding.statusTextView.visibility = View.GONE
                showSnackbar("Error: ${state.message}")
                viewModel.resetCaptureState()
            }
            else -> {
                binding.statusTextView.visibility = View.GONE
            }
        }
    }

    private fun updateFlashButtonIcon(mode: FlashMode) {
        val iconRes = when (mode) {
            FlashMode.ON -> com.swifstagrime.core_ui.R.drawable.ic_flash
            FlashMode.OFF -> com.swifstagrime.core_ui.R.drawable.ic_flash
            FlashMode.AUTO -> com.swifstagrime.core_ui.R.drawable.ic_flash
        }
        binding.flashButton.setImageResource(iconRes)
    }


    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSnackbarWithAction(message: String, actionText: String, action: (View) -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(actionText, action)
            .show()
    }
}
