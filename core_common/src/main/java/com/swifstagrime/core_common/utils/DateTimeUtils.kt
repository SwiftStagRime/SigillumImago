package com.swifstagrime.core_common.utils

import com.swifstagrime.core_common.constants.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

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

    fun formatDurationMillis(millis: Long): String {
        if (millis <= 0) {
            return "00:00"
        }

        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60 // Minutes part
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60 // Seconds part

        return if (hours > 0) {
            // Format as HH:MM:SS
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            // Format as MM:SS
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}