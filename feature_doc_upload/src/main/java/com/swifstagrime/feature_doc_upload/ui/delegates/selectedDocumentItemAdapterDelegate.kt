package com.swifstagrime.feature_doc_upload.ui.delegates

import android.text.format.Formatter
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_doc_upload.databinding.ItemSelectedDocumentBinding
import com.swifstagrime.feature_doc_upload.domain.models.SelectedDocument

fun selectedDocumentItemAdapterDelegate(
    onRemoveClicked: (SelectedDocument) -> Unit
) = adapterDelegateViewBinding<SelectedDocument, SelectedDocument, ItemSelectedDocumentBinding>(
    { layoutInflater, parent -> ItemSelectedDocumentBinding.inflate(layoutInflater, parent, false) }
) {

    binding.removeFileButton.setOnClickListener {
        onRemoveClicked(item)
    }

    bind {
        val currentItem = item

        binding.fileNameTextView.text = currentItem.fileName

        binding.fileSizeTextView.text = if (currentItem.sizeBytes >= 0) {
            Formatter.formatShortFileSize(context, currentItem.sizeBytes)
        } else {
            context.getString(R.string.unknown_size)
        }

        binding.fileIconImageView.setImageResource(R.drawable.ic_documents)
    }
}