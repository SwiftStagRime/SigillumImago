package com.swifstagrime.core_common.model

enum class MediaType(val mimeTypePrefix: String, val defaultFileExtension: String) {
    PHOTO("image/", ".jpg"),
    AUDIO("audio/", ".m4a"),
    DOCUMENT("application/", ".pdf");

    companion object {
        fun fromMimeType(mimeType: String?): MediaType? {
            return entries.find { mimeType?.startsWith(it.mimeTypePrefix, ignoreCase = true) == true }
        }

        fun fromFilename(filename: String?): MediaType? {
            val extension = filename
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotEmpty() }
                ?.lowercase()
                ?: return null

            val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
            val audioExtensions = setOf("aac", "m4a", "mp3", "ogg", "wav", "amr", "flac")

            return when {
                extension in photoExtensions -> PHOTO
                extension in audioExtensions -> AUDIO
                else -> DOCUMENT
            }
        }
    }
}