package com.swifstagrime.core_common.utils

import android.util.Log
import com.swifstagrime.core_common.constants.Constants
import java.io.File
import android.content.Context


object CacheUtils {

    fun clearInternalCacheDirectory(context: Context): Int {
        val cacheDir = context.cacheDir
        return if (cacheDir != null && cacheDir.isDirectory) {
            deleteDirectoryContents(cacheDir)
        } else {
            -1
        }
    }

    private fun deleteDirectoryContents(directory: File): Int {
        var deleteCount = 0
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        deleteCount += deleteDirectoryContents(file)
                    }
                    if (file.delete()) {
                        deleteCount++
                    } else {
                        Log.w(Constants.APP_TAG, "Failed to delete: ${file.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(Constants.APP_TAG, "Error while clearing cache contents in ${directory.path}", e)
        }
        return deleteCount
    }

}