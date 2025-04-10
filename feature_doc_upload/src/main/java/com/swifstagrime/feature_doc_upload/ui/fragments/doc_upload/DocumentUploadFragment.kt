package com.swifstagrime.feature_doc_upload.ui.fragments.doc_upload

import androidx.fragment.app.viewModels
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_doc_upload.databinding.FragmentDocumentUploadBinding
import com.swifstagrime.feature_doc_upload.domain.models.SelectedDocument
import com.swifstagrime.feature_doc_upload.ui.delegates.selectedDocumentItemAdapterDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class DocumentUploadFragment : BaseFragment<FragmentDocumentUploadBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentDocumentUploadBinding
        get() = FragmentDocumentUploadBinding::inflate

    private val viewModel: DocumentUploadViewModel by viewModels()

    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

    private val selectedFilesAdapter: ListDelegationAdapter<List<SelectedDocument>> by lazy {
        ListDelegationAdapter(
            selectedDocumentItemAdapterDelegate(
                onRemoveClicked = { selectedDoc -> viewModel.onRemoveFileClicked(selectedDoc.id) }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFilePicker()
    }

    override fun setupViews() {
        binding.selectedFilesRecyclerView.apply {
            adapter = selectedFilesAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.promptCardView.setOnClickListener { viewModel.onAddFilesClicked() }
        binding.addMoreFilesButton.setOnClickListener { viewModel.onAddFilesClicked() }
        binding.uploadFilesButton.setOnClickListener { viewModel.onUploadClicked() }
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.uiState.collectLatest { state ->
                        handleUiState(state)
                    }
                }

                launch {
                    viewModel.uiEvents.collect { event ->
                        handleUiEvent(event)
                    }
                }
            }
        }
    }

    private fun setupFilePicker() {
        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (!uris.isNullOrEmpty()) {
                viewModel.onFilesSelected(uris)
            } else {
                Log.d(Constants.APP_TAG, "File Picker returned null or empty list.")
            }
        }
    }

    private fun handleUiState(state: DocumentUploadUiState) {

        binding.promptCardView.isVisible = state is DocumentUploadUiState.Idle
        binding.selectedFilesCardView.isVisible = state is DocumentUploadUiState.FilesSelected || state is DocumentUploadUiState.Uploading
        binding.uploadProgressBar.isVisible = state is DocumentUploadUiState.Uploading
        binding.uploadStatusTextView.isVisible = state is DocumentUploadUiState.Uploading
        binding.errorTextView.isVisible = state is DocumentUploadUiState.Error

        binding.uploadFilesButton.isEnabled = state is DocumentUploadUiState.FilesSelected

        when (state) {
            is DocumentUploadUiState.Idle -> {
            }
            is DocumentUploadUiState.FilesSelected -> {
                selectedFilesAdapter.items = state.documents
                selectedFilesAdapter.notifyDataSetChanged()
            }
            is DocumentUploadUiState.Uploading -> {

                binding.uploadProgressBar.progress = state.progress.overallProgressPercent

                val etaString = state.progress.estimatedTimeRemainingMillis?.let { etaMs ->
                    formatEta(etaMs)
                } ?: getString(R.string.eta_calculating)

                binding.uploadStatusTextView.text = getString(
                    R.string.upload_status_format,
                    state.progress.currentFileIndex + 1,
                    state.progress.totalFiles,
                    state.progress.currentFileName,
                    etaString
                )
            }
            is DocumentUploadUiState.Error -> {
                binding.errorTextView.text = state.message
            }
        }
    }

    private fun handleUiEvent(event: UiEvent) {
        Log.d(Constants.APP_TAG, "Handling UI Event: ${event::class.simpleName}")
        when (event) {
            is UiEvent.RequestFileSelection -> {
                try {
                    filePickerLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    Log.e(Constants.APP_TAG, "Failed to launch file picker", e)
                    showSnackbar(getString(R.string.error_launching_file_picker))
                }
            }
            is UiEvent.ShowUploadComplete -> {
                showSnackbar(getString(R.string.upload_complete))
            }
            is UiEvent.ShowErrorSnackbar -> {
                showSnackbar(event.message)
            }
        }
    }

    private fun formatEta(millis: Long): String {
        if (millis < 0) return ""
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return when {
            minutes > 0 -> getString(R.string.eta_minutes_seconds, minutes, seconds)
            totalSeconds > 0 -> getString(R.string.eta_seconds, seconds)
            else -> getString(R.string.eta_less_than_second)
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}