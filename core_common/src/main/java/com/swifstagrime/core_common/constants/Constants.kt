package com.swifstagrime.core_common.constants

object Constants {

    const val APP_TAG = "SigillumImago"

    const val BASE_STORAGE_DIRECTORY_NAME = "secure_media"
    const val ENCRYPTED_FILE_EXTENSION = ".enc"

    const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val MASTER_KEY_ALIAS = "secure_photos_master_key"

    const val ENCRYPTION_ALGORITHM = "AES"
    const val ENCRYPTION_BLOCK_MODE = "GCM"
    const val ENCRYPTION_PADDING = "NoPadding"
    const val ENCRYPTION_TRANSFORMATION = "$ENCRYPTION_ALGORITHM/$ENCRYPTION_BLOCK_MODE/$ENCRYPTION_PADDING"

    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_IV_LENGTH_BYTES = 12

    const val DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

}