package com.swifstagrime.core_data_impl.repository

import android.content.Context
import android.util.Log
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.DataNotFoundException
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.model.StorageIOException
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_common.utils.wrapResultSync
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_data_api.model.MediaMetadata
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.core_data_impl.crypto.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
) : SecureMediaRepository {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secureDir by lazy {
        File(context.filesDir, Constants.BASE_STORAGE_DIRECTORY_NAME).apply { mkdirs() }
    }

    private val _mediaFilesFlow = MutableStateFlow<List<MediaFile>>(emptyList())

    init {
        refreshMediaFileList()
    }

    override fun getAllMediaFiles(): Flow<List<MediaFile>> = _mediaFilesFlow.asStateFlow()

    private fun refreshMediaFileList() {
        repositoryScope.launch {
            val result = loadMediaFilesFromDisk()
            if (result is Result.Success) {
                _mediaFilesFlow.value = result.data
            } else if (result is Result.Error) {
                _mediaFilesFlow.value = emptyList()
            }
        }
    }

    private suspend fun loadMediaFilesFromDisk(): Result<List<MediaFile>> =
        withContext(Dispatchers.IO) {
            wrapResultSync {
                if (!secureDir.exists() || !secureDir.isDirectory) {
                    return@wrapResultSync emptyList()
                }

                secureDir.listFiles { _, name -> name.endsWith(Constants.ENCRYPTED_FILE_EXTENSION) }
                    ?.mapNotNull { encryptedFile ->
                        parseMediaFile(encryptedFile)
                    }
                    ?.sortedByDescending { it.createdAtTimestampMillis }
                    ?: emptyList()
            }
        }

    private fun parseMediaFile(encryptedFile: File): MediaFile? {
        try {
            val baseNameWithExtension =
                encryptedFile.nameWithoutExtension
            val metaFile = File(secureDir, "$baseNameWithExtension.meta")

            if (!metaFile.exists()) {
                return null
            }

            val mediaType =
                MediaType.fromFilename(baseNameWithExtension) ?: MediaType.DOCUMENT
            val createdAt =
                encryptedFile.lastModified()

            val metaContent = metaFile.readText()
            val metadata = MediaMetadata.fromFileContent(metaContent)

            if (metadata == null) {
                Log.w(Constants.APP_TAG, "Could not parse metadata for ${metaFile.name}")
                return null
            }

            return MediaFile(
                fileName = baseNameWithExtension,
                mediaType = mediaType,
                createdAtTimestampMillis = createdAt,
                sizeBytes = metadata.originalSizeBytes
            )
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun saveMedia(
        desiredFileName: String,
        mediaType: MediaType,
        data: ByteArray
    ): Result<MediaFile> = withContext(Dispatchers.IO) {

        val timestamp = System.currentTimeMillis()
        val originalExtension = desiredFileName.substringAfterLast('.', "")
        val safeExtension =
            if (originalExtension.isNotEmpty()) ".$originalExtension" else mediaType.defaultFileExtension
        val uniqueBaseName = "${timestamp}_${UUID.randomUUID()}$safeExtension"

        val encryptedFile = File(secureDir, uniqueBaseName + Constants.ENCRYPTED_FILE_EXTENSION)
        val metaFile = File(secureDir, "$uniqueBaseName.meta")

        val encryptResult = encryptionManager.encryptDataToFile(data, encryptedFile)
        if (encryptResult is Result.Error) {
            return@withContext Result.Error(encryptResult.exception)
        }

        val metadata = MediaMetadata(originalSizeBytes = data.size.toLong())
        val metaSaveResult = wrapResultSync<Unit> {
            try {
                metaFile.writeText(metadata.toFileContent())
            } catch (e: IOException) {
                encryptedFile.delete()
                throw StorageIOException("Failed to write metadata for $uniqueBaseName", e)
            }
        }

        if (metaSaveResult is Result.Error) {
            encryptedFile.delete()
            return@withContext Result.Error(metaSaveResult.exception)
        }

        val savedMediaFile = MediaFile(
            fileName = uniqueBaseName,
            mediaType = mediaType,
            createdAtTimestampMillis = timestamp,
            sizeBytes = metadata.originalSizeBytes
        )

        refreshMediaFileList()
        Result.Success(savedMediaFile)
    }

    override suspend fun getDecryptedMediaData(fileName: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val encryptedFile = File(secureDir, fileName + Constants.ENCRYPTED_FILE_EXTENSION)

            if (!encryptedFile.exists()) {
                return@withContext Result.Error(DataNotFoundException("File not found: $fileName"))
            }

            val decryptResult = encryptionManager.decryptDataFromFile(encryptedFile)

            decryptResult
        }


    override suspend fun deleteMedia(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val encryptedFile = File(secureDir, fileName + Constants.ENCRYPTED_FILE_EXTENSION)
        val metaFile = File(secureDir, "$fileName.meta")
        var deletedEnc = true
        var deletedMeta = true
        var error: Throwable? = null

        try {
            if (encryptedFile.exists()) {
                deletedEnc = encryptedFile.delete()
                if (!deletedEnc) {
                    Log.w(
                        Constants.APP_TAG,
                        "Failed to delete encrypted file: ${encryptedFile.name}"
                    )
                }
            }
            if (metaFile.exists()) {
                deletedMeta = metaFile.delete()
                if (!deletedMeta) {
                    Log.w(Constants.APP_TAG, "Failed to delete metadata file: ${metaFile.name}")
                }
            }


            if (!encryptedFile.exists() && !metaFile.exists()) {
                refreshMediaFileList()
                Result.Success(Unit)
            } else {
                error =
                    StorageIOException("Failed to delete one or both files for $fileName. Encrypted exists: ${encryptedFile.exists()}, Meta exists: ${metaFile.exists()}")
                Result.Error(error)
            }

        } catch (e: Exception) {
            Result.Error(StorageIOException("Failed to delete files for $fileName", e))
        }
    }

    override suspend fun getMediaFile(fileName: String): Result<MediaFile> =
        withContext(Dispatchers.IO) {
            val encryptedFile = File(secureDir, fileName + Constants.ENCRYPTED_FILE_EXTENSION)
            if (!encryptedFile.exists()) {
                return@withContext Result.Error(DataNotFoundException("File not found: $fileName"))
            }
            val mediaFile = parseMediaFile(encryptedFile)
            if (mediaFile != null) {
                Result.Success(mediaFile)
            } else {
                Result.Error(DataNotFoundException("Metadata parsing failed for $fileName"))
            }
        }

    override suspend fun deleteAllMedia(): Result<Unit> = withContext(Dispatchers.IO) {
        var allDeleted = true
        var firstError: Throwable? = null

        try {
            secureDir.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    allDeleted = false
                    if (firstError == null) firstError =
                        StorageIOException("Failed to delete ${file.name}")
                }
            }

            refreshMediaFileList()
            if (allDeleted) {
                Result.Success(Unit)
            } else {
                Result.Error(
                    firstError ?: StorageIOException("Failed to delete one or more files.")
                )
            }
        } catch (e: Exception) {
            refreshMediaFileList()
            Result.Error(StorageIOException("Error occurred during deleteAllMedia", e))
        }
    }
}