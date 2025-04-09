package com.swifstagrime.feature_recordings.domain.models

import com.swifstagrime.core_data_api.model.MediaFile

data class RecordingDisplayInfo(
    val mediaFile: MediaFile,
    val displayName: String
) {
    val internalFileName: String get() = mediaFile.fileName
    val createdAtTimestampMillis: Long get() = mediaFile.createdAtTimestampMillis
}