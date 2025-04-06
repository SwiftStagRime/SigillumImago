package com.swifstagrime.feature_gallery.ui.delegates

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import com.swifstagrime.core_common.constants.Constants
import com.swifstagrime.feature_gallery.databinding.ItemGalleryMediaBinding
import com.swifstagrime.feature_gallery.ui.fragments.GalleryItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.swifstagrime.core_common.utils.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

fun galleryItemAdapterDelegate(
    scope: LifecycleCoroutineScope,
    onItemClicked: (GalleryItem) -> Unit,
    targetViewSizePx: Int,
    loadThumbnail: suspend (fileName: String) -> Result<ByteArray>,
) = adapterDelegateViewBinding<GalleryItem, Any, ItemGalleryMediaBinding>(
    { layoutInflater, parent -> ItemGalleryMediaBinding.inflate(layoutInflater, parent, false) }
) {
    var currentLoadingJob: Job? = null
    var currentDecodingJob: Job? = null

    binding.root.setOnClickListener {
        item.let(onItemClicked)
    }

    bind {
        currentLoadingJob?.cancel()
        currentDecodingJob?.cancel()
        currentDecodingJob = null
        currentLoadingJob = null

        binding.mediaThumbnailImageView.setImageResource(0)

        currentLoadingJob = scope.launch {
            val result = try {
                loadThumbnail(item.fileName)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Constants.APP_TAG, "Exception calling loadThumbnail suspend fun for ${item.fileName}", e)
                Result.Error(e)
            }

            if (!isActive) return@launch

            when (result) {
                is Result.Success -> {
                    currentDecodingJob = launch(Dispatchers.Default) {
                        val decodedBitmap = decodeSampledBitmapFromByteArray(
                            result.data,
                            targetViewSizePx,
                            targetViewSizePx
                        )
                        if (!isActive) return@launch

                        withContext(Dispatchers.Main) {
                            if (decodedBitmap != null) {
                                binding.mediaThumbnailImageView.setImageBitmap(decodedBitmap)
                                binding.mediaThumbnailImageView.setBackgroundResource(0)
                            }
                        }
                    }
                    currentDecodingJob?.invokeOnCompletion { currentDecodingJob = null }
                }
                is Result.Error -> {
                    Log.e(Constants.APP_TAG, "loadThumbnail returned error for ${item.fileName}", result.exception)
                    if (!isActive) return@launch
                }
            }
        }
        currentLoadingJob?.invokeOnCompletion { currentLoadingJob = null }
    }

    onViewRecycled {
        currentLoadingJob?.cancel(CancellationException("ViewHolder recycled"))
        currentLoadingJob = null
        binding.mediaThumbnailImageView.setImageBitmap(null)
        binding.mediaThumbnailImageView.setImageResource(0)
    }
}

fun decodeSampledBitmapFromByteArray(
    data: ByteArray,
    reqWidth: Int,
    reqHeight: Int
): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false

        BitmapFactory.decodeByteArray(data, 0, data.size, options)
    } catch (e: Exception) {
        Log.e(Constants.APP_TAG, "Failed to decode sampled bitmap", e)
        null
    } catch (oom: OutOfMemoryError) {
        Log.e(Constants.APP_TAG, "OutOfMemoryError during bitmap decode", oom)
        null
    }
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }

        val totalPixels = width * height * 1.0
        val totalReqPixelsCap = reqWidth * reqHeight * 2.0

        while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
            inSampleSize++
        }

    }
    return inSampleSize
}