package com.swifstagrime.feature_recordings.ui.delegates

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import com.swifstagrime.core_common.utils.DateTimeUtils
import com.swifstagrime.core_common.utils.DateTimeUtils.formatDurationMillis
import com.swifstagrime.core_ui.R
import com.swifstagrime.feature_recordings.databinding.ItemRecordingBinding
import com.swifstagrime.feature_recordings.domain.models.RecordingDisplayInfo
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService.PlaybackState

fun recordingItemAdapterDelegate(
    onItemClicked: (RecordingDisplayInfo) -> Unit,
    onDeleteClicked: (RecordingDisplayInfo) -> Unit,
    getCurrentPlaybackState: () -> PlaybackState,
    getCurrentPositionForFile: (fileName: String) -> Long,
    getDurationForFile: (fileName: String) -> Long
) = adapterDelegateViewBinding<RecordingDisplayInfo, RecordingDisplayInfo, ItemRecordingBinding>(
    { layoutInflater, parent -> ItemRecordingBinding.inflate(layoutInflater, parent, false) }
) {

    binding.root.setOnClickListener {
        onItemClicked(item)
    }
    binding.deleteButton.setOnClickListener {
        onDeleteClicked(item)
    }

    bind {
        val currentItem: RecordingDisplayInfo = item
        val internalFileName = currentItem.internalFileName
        val globalPlaybackState = getCurrentPlaybackState()
        val isThisPlaying =
            globalPlaybackState is PlaybackState.Playing && globalPlaybackState.fileName == internalFileName
        val isThisPaused =
            globalPlaybackState is PlaybackState.Paused && globalPlaybackState.fileName == internalFileName
        val isThisReady =
            globalPlaybackState is PlaybackState.Ready && globalPlaybackState.fileName == internalFileName
        val isThisPreparing =
            globalPlaybackState is PlaybackState.Preparing && globalPlaybackState.fileName == internalFileName

        binding.recordingNameTextView.text = currentItem.displayName
        val formattedDate = DateTimeUtils.formatTimestamp(currentItem.createdAtTimestampMillis)
        val durationFromPlayer = getDurationForFile(internalFileName)
        val displayDuration =
            if (durationFromPlayer > 0) formatDurationMillis(durationFromPlayer) else "--:--"

        binding.recordingDetailsTextView.text = context.getString(
            R.string.recording_details_format,
            formattedDate,
            displayDuration
        )

        val playPauseIconRes = when {
            isThisPlaying -> R.drawable.ic_pause
            isThisPaused || isThisReady || isThisPreparing || globalPlaybackState is PlaybackState.Idle || globalPlaybackState is PlaybackState.Error -> R.drawable.ic_play
            else -> R.drawable.ic_play
        }
        binding.playPauseIndicator.setImageResource(playPauseIconRes)

        val progressPosition = getCurrentPositionForFile(internalFileName)
        if ((isThisPlaying || isThisPaused) && durationFromPlayer > 0) {
            binding.itemProgressBar.max = durationFromPlayer.toInt()
            binding.itemProgressBar.progress = progressPosition.toInt()
            binding.itemProgressBar.isVisible = true
        } else {
            binding.itemProgressBar.isVisible = false
            binding.itemProgressBar.progress = 0
        }
    }

    onViewRecycled {
        binding.itemProgressBar.isVisible = false
        binding.itemProgressBar.progress = 0
        binding.playPauseIndicator.setImageResource(R.drawable.ic_play)
    }


}