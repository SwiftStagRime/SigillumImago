package com.swifstagrime.core_common.model

open class SecureStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class EncryptionException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)

class DecryptionException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)

class StorageIOException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)

class MasterKeyUnavailableException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)

class DataNotFoundException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)