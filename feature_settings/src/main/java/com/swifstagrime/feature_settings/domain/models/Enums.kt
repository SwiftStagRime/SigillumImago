package com.swifstagrime.feature_settings.domain.models

enum class AppTheme {
    LIGHT, DARK, SYSTEM_DEFAULT
}

enum class LockMethod {
    NONE, // App lock disabled
    PIN,
    BIOMETRIC
}