package com.swifstagrime.feature_documents.ui.delegates

import android.text.format.Formatter
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import com.swifstagrime.core_common.utils.DateTimeUtils
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_documents.databinding.ItemDocumentBinding
import com.swifstagrime.feature_documents.domain.models.DocumentDisplayInfo

fun documentItemAdapterDelegate(
    onItemClicked: (DocumentDisplayInfo) -> Unit,
    onDeleteClicked: (DocumentDisplayInfo) -> Unit
) = adapterDelegateViewBinding<DocumentDisplayInfo, DocumentDisplayInfo, ItemDocumentBinding>(
    { layoutInflater, parent -> ItemDocumentBinding.inflate(layoutInflater, parent, false) }
) {

    binding.root.setOnClickListener {
        onItemClicked(item)
    }
    binding.deleteButton.setOnClickListener {
        onDeleteClicked(item)
    }

    bind {
        val currentItem = item

        binding.documentNameTextView.text = currentItem.displayName


        val formattedDate = DateTimeUtils.formatTimestamp(currentItem.createdAtTimestampMillis)
        val formattedSize = Formatter.formatShortFileSize(context, currentItem.sizeBytes)
        binding.documentDetailsTextView.text = context.getString(
            R.string.document_details_format,
            formattedDate,
            formattedSize
        )

        binding.documentIconImageView.setImageResource(R.drawable.ic_documents)
    }

}