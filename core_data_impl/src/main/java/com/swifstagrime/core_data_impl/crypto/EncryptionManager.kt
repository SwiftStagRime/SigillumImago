package com.swifstagrime.core_data_impl.crypto

import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey
import androidx.security.crypto.EncryptedFile
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.core_common.model.DecryptionException
import com.swifstagrime.core_common.model.EncryptionException
import com.swifstagrime.core_common.model.MasterKeyUnavailableException
import com.swifstagrime.core_common.utils.wrapResultSync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.fold

@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey: MasterKey by lazy {
        try {
            MasterKey.Builder(context, Constants.MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            throw MasterKeyUnavailableException("Failed to get or create master key", e)
        }
    }

    private fun createEncryptedFileOutput(file: File): Result<EncryptedFile> = wrapResultSync {
        try {
            EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
        } catch (e: Exception) {

            throw EncryptionException("Failed to initialize encrypted file for writing: ${file.name}", e)
        }
    }

    private fun createEncryptedFileInput(file: File): Result<EncryptedFile> = wrapResultSync {
        try {
            EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
        } catch (e: Exception) {
            throw DecryptionException("Failed to initialize encrypted file for reading: ${file.name}", e)
        }
    }

    fun encryptDataToFile(data: ByteArray, targetFile: File): Result<Unit> {
        return createEncryptedFileOutput(targetFile).fold(
            onSuccess = { encryptedFile: EncryptedFile ->
                wrapResultSync {
                    try {
                        targetFile.parentFile?.mkdirs()

                        encryptedFile.openFileOutput().use { outputStream ->
                            outputStream.write(data)
                            outputStream.flush()
                        }
                    } catch (ioe: IOException) {
                        throw EncryptionException("Failed to write encrypted data to ${targetFile.name}", ioe)
                    } catch (e: Exception) {
                        throw EncryptionException("Failed to encrypt data for ${targetFile.name}", e)
                    }
                }
            },
            onFailure = { exception ->
                Result.Error(exception)
            }
        )
    }

    fun decryptDataFromFile(sourceFile: File): Result<ByteArray> {
        if (!sourceFile.exists()) {
            return Result.Error(DecryptionException("Encrypted file not found: ${sourceFile.name}"))
        }

        return createEncryptedFileInput(sourceFile).fold(
            onSuccess = { encryptedFile ->
                wrapResultSync {
                    try {
                        val byteStream = ByteArrayOutputStream()
                        encryptedFile.openFileInput().use { inputStream ->
                            inputStream.copyTo(byteStream)
                        }
                        byteStream.toByteArray()
                    } catch (ioe: IOException) {
                        throw DecryptionException("Failed to read encrypted data from ${sourceFile.name}", ioe)
                    } catch (e: Exception) {
                        throw DecryptionException("Failed to decrypt data from ${sourceFile.name}", e)
                    }
                }
            },
            onFailure = { exception ->
                Result.Error(exception)
            }
        )
    }
}