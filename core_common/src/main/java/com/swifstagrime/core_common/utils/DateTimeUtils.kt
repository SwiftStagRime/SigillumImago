package com.swifstagrime.core_common.utils

import com.swifstagrime.core_common.constants.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateTimeUtils {
    fun formatTimestamp(
        timestampMillis: Long,
        timeZone: TimeZone = TimeZone.getTimeZone("UTC")
    ): String {
        return try {
            val sdf = SimpleDateFormat(Constants.DEFAULT_DATETIME_FORMAT, Locale.getDefault())
            sdf.timeZone = timeZone
            sdf.format(Date(timestampMillis))
        } catch (e: Exception) {
            Date(timestampMillis).toString()
        }
    }

    fun getCurrentTimestampMillis(): Long {
        return System.currentTimeMillis()
    }
}