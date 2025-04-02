package com.swifstagrime.core_data_api.model

data class MediaMetadata(
    val originalSizeBytes: Long
) {
    companion object {
        fun fromFileContent(content: String): MediaMetadata? {
            return content.trim().toLongOrNull()?.let { MediaMetadata(it) }
        }
    }

    fun toFileContent(): String {
        return originalSizeBytes.toString()
    }
}