package com.swifstagrime.core_data_api.repository

import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_data_api.model.MediaFile
import kotlinx.coroutines.flow.Flow

interface SecureMediaRepository {
    suspend fun saveMedia(
        desiredFileName: String,
        mediaType: MediaType,
        data: ByteArray
    ): Result<MediaFile>

    fun getAllMediaFiles(): Flow<List<MediaFile>>
    suspend fun getDecryptedMediaData(fileName: String): Result<ByteArray>
    suspend fun deleteMedia(fileName: String): Result<Unit>
    suspend fun getMediaFile(fileName: String): Result<MediaFile>
    suspend fun deleteAllMedia(): Result<Unit>
    suspend fun getDecryptedDisplayName(internalFileName: String): Result<String?>
    suspend fun getTotalUsedStorageBytes(): Result<Long>
}