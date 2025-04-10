package com.swifstagrime.feature_recordings.ui.fragments.recordings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.swifstagrime.core_data_api.model.MediaFile
import com.swifstagrime.core_ui.R
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_recordings.databinding.FragmentRecordingsBinding
import com.swifstagrime.feature_recordings.domain.models.RecordingDisplayInfo
import com.swifstagrime.feature_recordings.domain.services.RecordingPlaybackService
import com.swifstagrime.feature_recordings.ui.delegates.recordingItemAdapterDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecordingsFragment : BaseFragment<FragmentRecordingsBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentRecordingsBinding
        get() = FragmentRecordingsBinding::inflate

    private val viewModel: RecordingsViewModel by viewModels()

    private val recordingsAdapter: ListDelegationAdapter<List<RecordingDisplayInfo>> by lazy {
        ListDelegationAdapter(
            recordingItemAdapterDelegate(
                onItemClicked = { displayInfo -> viewModel.onRecordingClicked(displayInfo.internalFileName) },
                onDeleteClicked = { displayInfo -> viewModel.onDeleteClicked(displayInfo.internalFileName) },
                getCurrentPlaybackState = { viewModel.playbackState.value },
                getCurrentPositionForFile = { internalFileName ->
                    val state = viewModel.playbackState.value
                    when (state) {
                        is RecordingPlaybackService.PlaybackState.Playing -> if (state.fileName == internalFileName) viewModel.currentPositionMs.value else 0L
                        is RecordingPlaybackService.PlaybackState.Paused -> if (state.fileName == internalFileName) viewModel.currentPositionMs.value else 0L
                        else -> 0L
                    }
                },
                getDurationForFile = { internalFileName ->
                    val state = viewModel.playbackState.value
                    when (state) {
                        is RecordingPlaybackService.PlaybackState.Ready -> if (state.fileName == internalFileName) state.durationMs else 0L
                        is RecordingPlaybackService.PlaybackState.Playing -> if (state.fileName == internalFileName) state.durationMs else 0L
                        is RecordingPlaybackService.PlaybackState.Paused -> if (state.fileName == internalFileName) state.durationMs else 0L
                        else -> 0L
                    }
                }
            )
        )
    }

    private var previousPlayingPosition: Int = RecyclerView.NO_POSITION
    private var currentPlayingPosition: Int = RecyclerView.NO_POSITION

    override fun setupViews() {
        binding.recordingsRecyclerView.apply {
            adapter = recordingsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.listState.collectLatest { state ->
                        handleListState(state)
                    }
                }

                launch {
                    viewModel.playbackState
                        .collect { state ->
                            updateItemsForPlaybackState(state)
                        }
                }

                launch {
                    combine(
                        viewModel.playbackState,
                        viewModel.currentPositionMs
                    ) { state, position -> Pair(state, position) }
                        .distinctUntilChanged()
                        .mapNotNull { (state, position) ->
                            if (state is RecordingPlaybackService.PlaybackState.Playing) {
                                findPositionByInternalFileName(state.fileName)
                            } else {
                                null
                            }
                        }
                        .distinctUntilChanged()
                        .collect { position ->
                            if (position != RecyclerView.NO_POSITION) {
                                recordingsAdapter.notifyItemChanged(
                                    position,
                                    PAYLOAD_PROGRESS_UPDATE
                                )
                            }
                        }
                }

                launch {
                    viewModel.uiEvents.collect { event ->
                        handleUiEvent(event)
                    }
                }
            }
        }
    }

    private fun handleListState(state: RecordingsListState) {
        binding.loadingProgressBar.isVisible = state is RecordingsListState.Loading
        binding.recordingsRecyclerView.isVisible = state is RecordingsListState.Success
        binding.statusTextView.isVisible =
            state is RecordingsListState.Error || (state is RecordingsListState.Success && state.recordings.isEmpty())

        when (state) {
            is RecordingsListState.Success -> {
                recordingsAdapter.items = state.recordings
                recordingsAdapter.notifyDataSetChanged()
                if (state.recordings.isEmpty()) {
                    binding.statusTextView.text = getString(R.string.no_recordings_found)
                }
                previousPlayingPosition = RecyclerView.NO_POSITION
                currentPlayingPosition = findPositionByInternalFileName(getCurrentPlayingFileName())
            }

            is RecordingsListState.Error -> {
                binding.statusTextView.text = state.message
            }

            RecordingsListState.Loading -> {
            }
        }
    }

    private fun findPositionByInternalFileName(internalFileName: String?): Int {
        if (internalFileName == null) return RecyclerView.NO_POSITION
        return recordingsAdapter.items?.indexOfFirst { it.mediaFile.fileName == internalFileName }
            ?: RecyclerView.NO_POSITION
    }

    private fun updateItemsForPlaybackState(state: RecordingPlaybackService.PlaybackState) {
        val newPlayingFileName = when (state) {
            is RecordingPlaybackService.PlaybackState.Playing -> state.fileName
            is RecordingPlaybackService.PlaybackState.Paused -> state.fileName
            is RecordingPlaybackService.PlaybackState.Ready -> state.fileName
            is RecordingPlaybackService.PlaybackState.Preparing -> state.fileName
            else -> null
        }

        val oldPosition = currentPlayingPosition
        val newPosition = findPositionByInternalFileName(newPlayingFileName)

        previousPlayingPosition = oldPosition
        currentPlayingPosition = newPosition

        if (oldPosition != RecyclerView.NO_POSITION && oldPosition != newPosition) {
            recordingsAdapter.notifyItemChanged(oldPosition, PAYLOAD_STATE_UPDATE)
        }
        if (newPosition != RecyclerView.NO_POSITION) {
            recordingsAdapter.notifyItemChanged(newPosition, PAYLOAD_STATE_UPDATE)
        }
    }

    private fun handleUiEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowDeleteConfirmation -> {
                showDeleteConfirmationDialog(event.fileName)
            }

            is UiEvent.ShowError -> {
                showSnackbar(event.message)
            }
        }
    }

    private fun showDeleteConfirmationDialog(fileName: String) {
        MaterialAlertDialogBuilder(requireContext(), com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded)
            .setTitle(com.swifstagrime.core_ui.R.string.dialog_delete_title)
            .setMessage(
                getString(
                    com.swifstagrime.core_ui.R.string.dialog_delete_message,
                    fileName
                )
            )
            .setNegativeButton(com.swifstagrime.core_ui.R.string.cancel, null)
            .setPositiveButton(com.swifstagrime.core_ui.R.string.delete) { _, _ ->
                viewModel.onDeleteConfirmed(fileName)
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun findPositionByFileName(fileName: String?): Int {
        if (fileName == null) return RecyclerView.NO_POSITION
        return recordingsAdapter.items?.indexOfFirst { it is MediaFile && it.fileName == fileName }
            ?: RecyclerView.NO_POSITION
    }

    private fun getCurrentPlayingFileName(): String? {
        return when (val state = viewModel.playbackState.value) {
            is RecordingPlaybackService.PlaybackState.Playing -> state.fileName
            is RecordingPlaybackService.PlaybackState.Paused -> state.fileName
            is RecordingPlaybackService.PlaybackState.Ready -> state.fileName
            is RecordingPlaybackService.PlaybackState.Preparing -> state.fileName
            else -> null
        }
    }

    companion object {
        private const val PAYLOAD_STATE_UPDATE = "payload_state_update"
        private const val PAYLOAD_PROGRESS_UPDATE = "payload_progress_update"
    }


}