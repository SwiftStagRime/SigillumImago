package com.swifstagrime.core_data_impl.repository

import android.content.Context
import android.util.Log
import com.swifstagrime.core_data_api.repository.SecureMediaRepository
import com.swifstagrime.core_data_impl.crypto.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.DataNotFoundException
import com.swifstagrime.core_common.model.MediaType
import com.swifstagrime.core_common.model.StorageIOException
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_common.utils.wrapResultSync
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_data_api.model.MediaMetadata
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

    // A coroutine scope tied to the lifecycle of the repository (Singleton)
    // Use SupervisorJob so failure of one job doesn't cancel others
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secureDir by lazy {
        File(context.filesDir, Constants.BASE_STORAGE_DIRECTORY_NAME).apply { mkdirs() }
    }

    // Internal state flow to hold the current list of media files
    // MutableStateFlow ensures the latest list is always available and emits updates
    private val _mediaFilesFlow = MutableStateFlow<List<MediaFile>>(emptyList())

    init {
        // Load initial list when the repository is created
        refreshMediaFileList()
    }

    // Public flow exposed to consumers
    override fun getAllMediaFiles(): Flow<List<MediaFile>> = _mediaFilesFlow.asStateFlow()

    /** Triggers a refresh of the media file list from storage. */
    private fun refreshMediaFileList() {
        repositoryScope.launch {
            val result = loadMediaFilesFromDisk()
            if (result is Result.Success) {
                _mediaFilesFlow.value = result.data
            } else if (result is Result.Error) {
                // Log error loading file list
                // Log.e(Constants.APP_TAG, "Error loading media file list", result.exception)
                // Optionally emit empty list or keep previous state on error?
                // Emitting empty list on severe error might be safer.
                _mediaFilesFlow.value = emptyList()
            }
        }
    }

    /** Reads the secure directory and constructs the list of MediaFile objects. */
    private suspend fun loadMediaFilesFromDisk(): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        wrapResultSync { // Using wrapResultSync as file ops aren't suspend funcs
            if (!secureDir.exists() || !secureDir.isDirectory) {
                // Log.w(Constants.APP_TAG, "Secure directory doesn't exist or isn't a directory.")
                return@wrapResultSync emptyList() // Return empty list if dir is bad
            }

            secureDir.listFiles { _, name -> name.endsWith(Constants.ENCRYPTED_FILE_EXTENSION) }
                ?.mapNotNull { encryptedFile ->
                    parseMediaFile(encryptedFile) // Use helper to parse each file
                }
                ?.sortedByDescending { it.createdAtTimestampMillis } // Sort by date descending
                ?: emptyList() // Return empty list if listFiles returns null
        }
    }

    /** Helper to parse metadata from filename and .meta file */
    private fun parseMediaFile(encryptedFile: File): MediaFile? {
        try {
            val baseNameWithExtension = encryptedFile.nameWithoutExtension // e.g., "photo_1678886400000_uuid.jpg"
            val metaFile = File(secureDir, "$baseNameWithExtension.meta")

            if (!metaFile.exists()) {
                // Log.w(Constants.APP_TAG, "Metadata file missing for ${encryptedFile.name}")
                return null // Cannot create MediaFile without metadata
            }

            // 1. Parse Filename (Example assumes format: type_timestamp_uuid.originalExt)
            // Robust parsing is needed here. Let's use a simpler approach first:
            // Use the full baseNameWithExtension to determine type via MediaType.fromFilename
            // Extract timestamp if embedded, otherwise maybe use file's last modified time?
            // For now, let's rely on MediaType.fromFilename and a stored timestamp if possible.
            // --> Let's refine the save process to store timestamp explicitly in meta file too.
            // --> For now, we will just use the filename itself as the unique ID passed around

            val mediaType = MediaType.fromFilename(baseNameWithExtension) ?: MediaType.DOCUMENT // Fallback
            val createdAt = encryptedFile.lastModified() // Use file mod time as creation time for now

            // 2. Read Metadata file
            val metaContent = metaFile.readText()
            val metadata = MediaMetadata.fromFileContent(metaContent)

            if (metadata == null) {
                // Log.w(Constants.APP_TAG, "Could not parse metadata for ${metaFile.name}")
                return null
            }

            return MediaFile(
                fileName = baseNameWithExtension, // The unique ID is the name without .enc
                mediaType = mediaType,
                createdAtTimestampMillis = createdAt, // Using file mod time - less accurate
                sizeBytes = metadata.originalSizeBytes
            )
        } catch (e: Exception) {
            // Log.e(Constants.APP_TAG, "Failed to parse media file info for ${encryptedFile.name}", e)
            return null // Skip this file if parsing fails
        }
    }

    override suspend fun saveMedia(
        desiredFileName: String,
        mediaType: MediaType,
        data: ByteArray
    ): Result<MediaFile> = withContext(Dispatchers.IO) {

        // 1. Generate a unique base filename (without .enc)
        // Format: timestamp_uuid.originalExtension
        // Using UUID ensures uniqueness even if saved at the exact same millisecond.
        val timestamp = System.currentTimeMillis()
        val originalExtension = desiredFileName.substringAfterLast('.', "")
        val safeExtension = if (originalExtension.isNotEmpty()) ".$originalExtension" else mediaType.defaultFileExtension
        val uniqueBaseName = "${timestamp}_${UUID.randomUUID()}$safeExtension"

        val encryptedFile = File(secureDir, uniqueBaseName + Constants.ENCRYPTED_FILE_EXTENSION)
        val metaFile = File(secureDir, "$uniqueBaseName.meta")

        // 2. Encrypt and save data
        val encryptResult = encryptionManager.encryptDataToFile(data, encryptedFile)
        if (encryptResult is Result.Error) {
            // Log.e(Constants.APP_TAG, "Encryption failed for $uniqueBaseName", encryptResult.exception)
            return@withContext Result.Error(encryptResult.exception) // Propagate encryption error
        }

        // 3. Create and save metadata
        val metadata = MediaMetadata(originalSizeBytes = data.size.toLong())
        val metaSaveResult = wrapResultSync<Unit> { // wrapResultSync for non-suspending file IO
            try {
                metaFile.writeText(metadata.toFileContent())
            } catch (e: IOException) {
                // Log.e(Constants.APP_TAG, "Failed to write metadata file for $uniqueBaseName", e)
                // Attempt to clean up the encrypted file if metadata write fails
                encryptedFile.delete()
                throw StorageIOException("Failed to write metadata for $uniqueBaseName", e)
            }
        }

        if (metaSaveResult is Result.Error) {
            // Log.e(Constants.APP_TAG, "Metadata saving failed for $uniqueBaseName", metaSaveResult.exception)
            // Encrypted file might already exist, attempt cleanup (best effort)
            encryptedFile.delete()
            return@withContext Result.Error(metaSaveResult.exception) // Propagate metadata error
        }

        // 4. Construct MediaFile object
        val savedMediaFile = MediaFile(
            fileName = uniqueBaseName,
            mediaType = mediaType, // Use the provided mediaType
            createdAtTimestampMillis = timestamp, // Use the generated timestamp
            sizeBytes = metadata.originalSizeBytes
        )

        // 5. Refresh the flow and return success
        refreshMediaFileList() // Trigger update in the background
        Result.Success(savedMediaFile)
    }

    override suspend fun getDecryptedMediaData(fileName: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val encryptedFile = File(secureDir, fileName + Constants.ENCRYPTED_FILE_EXTENSION)

        if (!encryptedFile.exists()) {
            return@withContext Result.Error(DataNotFoundException("File not found: $fileName"))
        }

        // Delegate decryption to EncryptionManager
        val decryptResult = encryptionManager.decryptDataFromFile(encryptedFile)

        // Map potential DecryptionException from manager if needed, or just pass through
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
                    // Log.w(Constants.APP_TAG, "Failed to delete encrypted file: ${encryptedFile.name}")
                }
            }
            if (metaFile.exists()) {
                deletedMeta = metaFile.delete()
                if (!deletedMeta) {
                    // Log.w(Constants.APP_TAG, "Failed to delete metadata file: ${metaFile.name}")
                }
            }

            // Consider success if files are gone or were deleted, even if one part failed but the other didn't exist
            if (!encryptedFile.exists() && !metaFile.exists()) {
                refreshMediaFileList() // Update flow
                Result.Success(Unit)
            } else {
                // If either delete failed and the file still exists
                error = StorageIOException("Failed to delete one or both files for $fileName. Encrypted exists: ${encryptedFile.exists()}, Meta exists: ${metaFile.exists()}")
                Result.Error(error)
            }

        } catch (e: Exception) {
            // Log.e(Constants.APP_TAG, "Error during file deletion for $fileName", e)
            Result.Error(StorageIOException("Failed to delete files for $fileName", e))
        }
    }

    override suspend fun getMediaFile(fileName: String): Result<MediaFile> = withContext(Dispatchers.IO) {
        val encryptedFile = File(secureDir, fileName + Constants.ENCRYPTED_FILE_EXTENSION)
        if (!encryptedFile.exists()) {
            return@withContext Result.Error(DataNotFoundException("File not found: $fileName"))
        }
        // Re-use the parsing logic. This might be slightly inefficient if called often,
        // but ensures consistency. Could optimize by checking the internal flow first.
        val mediaFile = parseMediaFile(encryptedFile)
        if (mediaFile != null) {
            Result.Success(mediaFile)
        } else {
            // Parsing failed, likely missing meta file or corruption
            Result.Error(DataNotFoundException("Metadata parsing failed for $fileName"))
        }
    }

    override suspend fun deleteAllMedia(): Result<Unit> = withContext(Dispatchers.IO) {
        var allDeleted = true
        var firstError: Throwable? = null

        try {
            secureDir.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    // Log.w(Constants.APP_TAG, "Failed to delete file during deleteAll: ${file.name}")
                    allDeleted = false
                    if(firstError == null) firstError = StorageIOException("Failed to delete ${file.name}")
                }
            }

            refreshMediaFileList() // Update flow

            if (allDeleted) {
                Result.Success(Unit)
            } else {
                Result.Error(firstError ?: StorageIOException("Failed to delete one or more files."))
            }
        } catch (e: Exception) {
            // Log.e(Constants.APP_TAG, "Error during deleteAllMedia", e)
            refreshMediaFileList() // Still refresh, some might have been deleted
            Result.Error(StorageIOException("Error occurred during deleteAllMedia", e))
        }
    }
}