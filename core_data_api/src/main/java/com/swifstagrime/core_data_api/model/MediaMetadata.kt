package com.swifstagrime.core_data_api.model

data class MediaMetadata(
    val originalSizeBytes: Long,
    val encryptedFileNameBase64: String? = null
) {
    companion object {
        private const val KEY_SIZE = "size"
        private const val KEY_NAME = "name"

        fun fromFileContent(content: String): MediaMetadata? {
            val properties = content.lines()
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }
                .toMap()

            val size = properties[KEY_SIZE]?.toLongOrNull() ?: return null
            val name = properties[KEY_NAME]

            return MediaMetadata(originalSizeBytes = size, encryptedFileNameBase64 = name)
        }
    }

    fun toFileContent(): String {
        val builder = StringBuilder()
        builder.append("$KEY_SIZE=$originalSizeBytes")
        encryptedFileNameBase64?.let {
            builder.append("\n$KEY_NAME=$it")
        }
        return builder.toString()
    }
}