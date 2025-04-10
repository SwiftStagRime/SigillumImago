package com.swifstagrime.feature_documents.ui.fragments.documents

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.fragment.app.viewModels
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_ui.R
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_documents.databinding.FragmentDocumentsBinding
import com.swifstagrime.feature_documents.domain.models.DocumentDisplayInfo
import com.swifstagrime.feature_documents.ui.delegates.documentItemAdapterDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@AndroidEntryPoint
class DocumentsFragment : BaseFragment<FragmentDocumentsBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentDocumentsBinding
        get() = FragmentDocumentsBinding::inflate

    private val viewModel: DocumentsViewModel by viewModels()

    private val documentsAdapter: ListDelegationAdapter<List<DocumentDisplayInfo>> by lazy {
        ListDelegationAdapter(
            documentItemAdapterDelegate(
                onItemClicked = { docInfo -> viewModel.onDocumentClicked(docInfo) },
                onDeleteClicked = { docInfo -> viewModel.onDeleteClicked(docInfo) }
            )
        )
    }


    override fun setupViews() {
        binding.documentsRecyclerView.apply {
            adapter = documentsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.listState.collectLatest { state ->
                        handleListState(state)
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


    private fun handleListState(state: DocumentsListState) {
        binding.loadingProgressBar.isVisible = state is DocumentsListState.Loading
        binding.documentsRecyclerView.isVisible = state is DocumentsListState.Success
        binding.statusTextView.isVisible = state is DocumentsListState.Error || (state is DocumentsListState.Success && state.documents.isEmpty())

        when (state) {
            is DocumentsListState.Success -> {
                documentsAdapter.items = state.documents
                documentsAdapter.notifyDataSetChanged()
                if (state.documents.isEmpty()) {
                    binding.statusTextView.text = getString(R.string.no_documents_found)
                }
            }
            is DocumentsListState.Error -> {
                binding.statusTextView.text = state.message
            }
            DocumentsListState.Loading -> {}
        }
    }

    private fun handleUiEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowDeleteConfirmation -> {
                showDeleteConfirmationDialog(event.internalFileName, event.displayName)
            }
            is UiEvent.PrepareToOpenFile -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    openFileExternally(event.decryptedData, event.originalDisplayName)
                }
            }
            is UiEvent.ShowError -> {
                showSnackbar(event.message)
                binding.loadingOverlay.isVisible = false
            }
            is UiEvent.ShowLoadingIndicator -> {
                binding.loadingOverlay.isVisible = true
            }
            is UiEvent.HideLoadingIndicator -> {
                binding.loadingOverlay.isVisible = false
            }
        }
    }

    private fun showDeleteConfirmationDialog(internalFileName: String, displayName: String) {
        MaterialAlertDialogBuilder(requireContext(), com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message_doc, displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.onDeleteConfirmed(internalFileName)
            }
            .show()
    }

    private fun showSnackbar(message: String, duration: Int = Snackbar.LENGTH_LONG) {
        Snackbar.make(binding.root, message, duration).show()
    }


    private suspend fun openFileExternally(data: ByteArray, suggestedFilename: String) {
        val context = requireContext()
        val cachePath = File(context.cacheDir, "temp_docs/")
        cachePath.mkdirs()

        val safeFilename = suggestedFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "temp_document" }
        val tempFile = File(cachePath, safeFilename)

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(data)
            }

            val authority = "${context.packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(context, authority, tempFile)


            val intent = Intent(Intent.ACTION_VIEW)
            val mimeType = getMimeType(safeFilename) ?: "*/*"
            intent.setDataAndType(fileUri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)


            withContext(Dispatchers.Main) {
                if (intent.resolveActivity(context.packageManager) != null) {
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Log.e(Constants.APP_TAG, "ActivityNotFoundException for ACTION_VIEW", e)
                        showSnackbar(getString(R.string.error_no_app_found_to_open, mimeType))
                    } catch (e: SecurityException) {
                        Log.e(Constants.APP_TAG, "SecurityException launching ACTION_VIEW", e)
                        showSnackbar(getString(R.string.error_opening_file_permission))
                    }
                } else {
                    Log.w(Constants.APP_TAG, "No activity found to handle MIME type: $mimeType")
                    showSnackbar(getString(R.string.error_no_app_found_to_open, mimeType))
                }
            }

        } catch (e: IOException) {
            Log.e(Constants.APP_TAG, "IOException writing temporary file", e)
            withContext(Dispatchers.Main) {
                showSnackbar(getString(R.string.error_creating_temp_file))
            }
        } catch (e: IllegalArgumentException) {
            Log.e(Constants.APP_TAG, "IllegalArgumentException getting FileProvider URI. Check authority/paths.xml", e)
            withContext(Dispatchers.Main) {
                showSnackbar(getString(R.string.error_opening_file_provider))
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun getMimeType(fileName: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
    }

}