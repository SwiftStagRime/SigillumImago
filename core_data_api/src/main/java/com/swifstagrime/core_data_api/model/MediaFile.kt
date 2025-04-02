package com.swifstagrime.core_data_api.model

import com.swifstagrime.core_common.model.MediaType
import java.io.Serializable

data class MediaFile(
    val fileName: String,
    val mediaType: MediaType,
    val createdAtTimestampMillis: Long,
    val sizeBytes: Long
) : Serializable