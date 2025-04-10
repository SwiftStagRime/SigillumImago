package com.swifstagrime.feature_doc_upload.domain.models

import android.net.Uri
import java.util.UUID

data class SelectedDocument(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
)