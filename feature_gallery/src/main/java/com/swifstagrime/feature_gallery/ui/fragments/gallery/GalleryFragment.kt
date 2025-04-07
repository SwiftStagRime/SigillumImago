package com.swifstagrime.feature_gallery.ui.fragments.gallery

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.swifstagrime.core_common.utils.Result
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_gallery.R
import com.swifstagrime.feature_gallery.databinding.FragmentGalleryBinding
import com.swifstagrime.feature_gallery.ui.decorators.GridSpacingItemDecoration
import com.swifstagrime.feature_gallery.ui.delegates.galleryItemAdapterDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GalleryFragment : BaseFragment<FragmentGalleryBinding>(), MenuProvider {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentGalleryBinding
        get() = FragmentGalleryBinding::inflate

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var galleryAdapter: ListDelegationAdapter<List<Any>>
    private var onBackPressedCallback: OnBackPressedCallback? = null


    override fun setupViews() {
        setupAdapter()
        setupRecyclerView()
        setupToolbar()
        setupBackButton()
    }

    private fun setupToolbar() {
        binding.toolbar.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        binding.toolbar.setNavigationOnClickListener {
            viewModel.exitSelectionMode()
        }
    }

    private fun setupAdapter() {
        val displayMetrics = resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        val spacingDp = 4f
        val spacingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, spacingDp, displayMetrics
        ).toInt()
        val spanCount = 4
        val totalHorizontalSpacing = (spanCount - 1) * spacingPx
        val imageViewSizePx =
            ((screenWidthPx - totalHorizontalSpacing) / spanCount).coerceAtLeast(100)

        galleryAdapter = ListDelegationAdapter(
            galleryItemAdapterDelegate(
                scope = viewLifecycleOwner.lifecycleScope,
                onItemClicked = ::handleGalleryItemClick,
                onItemLongClicked = ::handleGalleryItemLongClick,
                onItemSelectedToggled = ::handleItemSelectionToggle,
                isSelectionActive = { viewModel.isSelectionModeActive.value },
                selectedItemIds = { viewModel.selectedItems.value },
                loadThumbnail = ::loadThumbnailDataForDelegate,
                targetViewSizePx = imageViewSizePx
            )
        )
    }

    private fun setupBackButton() {
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (viewModel.isSelectionModeActive.value) {
                    viewModel.exitSelectionMode()
                }
            }
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
        }
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUi(state)
                    }
                }
                launch {
                    viewModel.isSelectionModeActive.collect { isActive ->
                        updateToolbarVisibility(isActive)
                        onBackPressedCallback?.isEnabled = isActive
                        requireActivity().invalidateOptionsMenu()
                        if (!isActive && galleryAdapter.itemCount > 0) {
                            galleryAdapter.notifyDataSetChanged()
                        }
                    }
                }
                launch {
                    viewModel.selectedItems.collect { selectedItems ->
                        updateToolbarTitle(selectedItems.size)
                        if (viewModel.isSelectionModeActive.value) {
                            galleryAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        val spacingDp = 4f
        val spacingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, spacingDp, resources.displayMetrics
        ).toInt()
        val spanCount = 4

        binding.galleryRecyclerView.apply {
            if (!::galleryAdapter.isInitialized) {
                return
            }
            adapter = galleryAdapter
            layoutManager = GridLayoutManager(requireContext(), spanCount)
            setHasFixedSize(true)
            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx))
            itemAnimator = null
        }
    }

    private fun updateUi(state: GalleryUiState) {
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        binding.loadingProgressBar.isVisible = state is GalleryUiState.Loading
        binding.errorTextView.isVisible = state is GalleryUiState.Error
        binding.emptyStateTextView.isVisible = state is GalleryUiState.Success && state.isEmpty
        binding.galleryRecyclerView.isVisible = state is GalleryUiState.Success

        when (state) {
            is GalleryUiState.Success -> {
                binding.loadingProgressBar.isVisible = false
                galleryAdapter.items = state.mediaItems
                binding.emptyStateTextView.isVisible = state.isEmpty
                galleryAdapter.notifyDataSetChanged()
            }

            is GalleryUiState.Error -> {
                binding.errorTextView.text = state.message
                binding.loadingProgressBar.isVisible = false
            }

            GalleryUiState.Loading -> {
                binding.loadingProgressBar.isVisible = true
            }
        }
    }

    private suspend fun loadThumbnailDataForDelegate(fileName: String): Result<ByteArray> {
        return viewModel.loadThumbnailData(fileName)
    }

    private fun handleGalleryItemClick(item: GalleryItem) {
        val action = GalleryFragmentDirections.actionNavigationGalleryToNavigationPhoto(
            fileName = item.fileName
        )
        findNavController().navigate(action)
    }

    private fun handleGalleryItemLongClick(item: GalleryItem) {
        viewModel.enterSelectionMode(item.fileName)
    }

    private fun handleItemSelectionToggle(item: GalleryItem) {
        viewModel.toggleSelection(item.fileName)
    }

    private fun updateToolbarVisibility(isVisible: Boolean) {
        binding.appBarLayout.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
    }

    private fun updateToolbarTitle(count: Int) {
        if (viewModel.isSelectionModeActive.value) {
            binding.toolbar.title =
                getString(com.swifstagrime.core_ui.R.string.toolbar_title_selected_items, count)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        if (viewModel.isSelectionModeActive.value) {
            menuInflater.inflate(R.menu.menu_gallery_selection, menu)
        }
    }

    override fun onPrepareMenu(menu: Menu) {
    }


    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (!viewModel.isSelectionModeActive.value) return false

        return when (menuItem.itemId) {
            R.id.action_delete -> {
                viewModel.deleteSelectedItems()
                true
            }

            else -> false
        }
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
        binding.galleryRecyclerView.adapter = null
        super.onDestroyView()
    }
}