package com.swifstagrime.feature_gallery.ui.fragments

import androidx.fragment.app.viewModels
import android.os.Bundle
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_gallery.R
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.feature_gallery.databinding.FragmentGalleryBinding
import com.swifstagrime.feature_gallery.ui.decorators.GridSpacingItemDecoration
import com.swifstagrime.feature_gallery.ui.delegates.galleryItemAdapterDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GalleryFragment : BaseFragment<FragmentGalleryBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentGalleryBinding
        get() = FragmentGalleryBinding::inflate

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var galleryAdapter: ListDelegationAdapter<List<Any>>


    override fun setupViews() {
        setupRecyclerView()
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        val displayMetrics = resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        val spacingDp = 4f
        val spacingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            spacingDp,
            resources.displayMetrics
        ).toInt()
        val spanCount = 4
        val totalHorizontalSpacing = (spanCount - 1) * spacingPx
        val imageViewSizePx = ((screenWidthPx - totalHorizontalSpacing) / spanCount).coerceAtLeast(100)
        galleryAdapter =
            ListDelegationAdapter(
                galleryItemAdapterDelegate(
                    scope = viewLifecycleOwner.lifecycleScope,
                    onItemClicked = ::handleGalleryItemClick,
                    loadThumbnail = ::loadThumbnailDataForDelegate,
                    targetViewSizePx = imageViewSizePx
                )
            )
        binding.galleryRecyclerView.apply {
            adapter = galleryAdapter
            layoutManager = GridLayoutManager(requireContext(), spanCount)
            setHasFixedSize(true)
            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx))
        }
    }

    private fun updateUi(state: GalleryUiState) {
        binding.loadingProgressBar.isVisible = state is GalleryUiState.Loading
        binding.errorTextView.isVisible = state is GalleryUiState.Error
        binding.emptyStateTextView.isVisible = state is GalleryUiState.Success && state.isEmpty
        binding.galleryRecyclerView.isVisible = state is GalleryUiState.Success && !state.isEmpty

        when (state) {
            is GalleryUiState.Success -> {
                galleryAdapter.items = state.mediaItems
                binding.emptyStateTextView.isVisible = state.isEmpty
            }
            is GalleryUiState.Error -> {
                binding.errorTextView.text = state.message
            }
            GalleryUiState.Loading -> { }
        }
    }

    private suspend fun loadThumbnailDataForDelegate(fileName: String): Result<ByteArray> {
        return viewModel.loadThumbnailData(fileName)
    }

    private fun handleGalleryItemClick(item: GalleryItem) {
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
}