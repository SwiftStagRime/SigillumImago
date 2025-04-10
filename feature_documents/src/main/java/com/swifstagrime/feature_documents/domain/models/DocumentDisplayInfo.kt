package com.swifstagrime.feature_documents.domain.models

import com.swifstagrime.core_data_api.model.MediaFile

data class DocumentDisplayInfo(
    val mediaFile: MediaFile,
    val displayName: String
) {
    val internalFileName: String get() = mediaFile.fileName
    val createdAtTimestampMillis: Long get() = mediaFile.createdAtTimestampMillis
    val sizeBytes: Long get() = mediaFile.sizeBytes
}