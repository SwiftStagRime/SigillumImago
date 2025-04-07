package com.swifstagrime.feature_gallery.ui.fragments.photo_detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_gallery.R
import com.swifstagrime.feature_gallery.databinding.FragmentPhotoDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhotoDetailFragment : BaseFragment<FragmentPhotoDetailBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentPhotoDetailBinding
        get() = FragmentPhotoDetailBinding::inflate

    private val viewModel: PhotoDetailViewModel by viewModels()

    private var photoDetailMenuProvider: MenuProvider? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
    }

    override fun setupViews() {
        setupToolbarBasics()
        viewModel.loadPhoto()
    }

    override fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.photoLoadState.collect { state ->
                        handlePhotoLoadState(state)
                    }
                }

                launch {
                    viewModel.deleteState.collect { state ->
                        handleDeleteState(state)
                    }
                }
            }
        }
    }


    private fun setupToolbarBasics() {
        (requireActivity() as? AppCompatActivity)?.setSupportActionBar(binding.photoDetailToolbar)

        (requireActivity() as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.photoDetailToolbar.title = ""

        binding.photoDetailToolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupMenu() {
        photoDetailMenuProvider?.let { requireActivity().removeMenuProvider(it) }

        val menuHost: MenuHost = requireActivity()

        photoDetailMenuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_photo_detail, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_delete_photo -> {
                        showDeleteConfirmationDialog()
                        true
                    }

                    else -> false
                }
            }

            override fun onPrepareMenu(menu: Menu) {
                super.onPrepareMenu(menu)
            }
        }

        menuHost.addMenuProvider(
            photoDetailMenuProvider!!,
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun handlePhotoLoadState(state: PhotoLoadState) {
        binding.loadingIndicator.visibility =
            if (state is PhotoLoadState.Loading) View.VISIBLE else View.GONE
        binding.errorTextView.visibility =
            if (state is PhotoLoadState.Error) View.VISIBLE else View.GONE
        binding.photoImageView.visibility =
            if (state is PhotoLoadState.Success) View.VISIBLE else View.INVISIBLE

        when (state) {
            is PhotoLoadState.Success -> {
                binding.photoImageView.setImageBitmap(state.bitmap)
                requireActivity().invalidateOptionsMenu()
            }

            is PhotoLoadState.Error -> {
                binding.errorTextView.text = state.message
                binding.photoImageView.setImageDrawable(null)
                requireActivity().invalidateOptionsMenu()
            }

            PhotoLoadState.Loading -> {
                binding.photoImageView.setImageDrawable(null)
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    private fun handleDeleteState(state: DeleteState) {
        when (state) {
            DeleteState.Deleting -> {
                requireActivity().invalidateOptionsMenu()
            }

            DeleteState.Deleted -> {
                findNavController().popBackStack()
            }

            is DeleteState.Error -> {
                showSnackbar(state.message)
                viewModel.resetDeleteState()
                requireActivity().invalidateOptionsMenu()
            }

            DeleteState.Idle -> {
                requireActivity().invalidateOptionsMenu()
            }
        }
    }


    private fun showDeleteConfirmationDialog() {
        if (viewModel.deleteState.value != DeleteState.Idle) return

        MaterialAlertDialogBuilder(
            requireContext(),
            com.swifstagrime.core_ui.R.style.AppTheme_Dialog_Rounded
        )
            .setTitle(getString(com.swifstagrime.core_ui.R.string.delete_confirmation_title))
            .setMessage(getString(com.swifstagrime.core_ui.R.string.delete_confirmation_message))
            .setPositiveButton(getString(com.swifstagrime.core_ui.R.string.delete_confirm_button)) { _, _ ->
                viewModel.deletePhoto()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }


    override fun onDestroyView() {
        photoDetailMenuProvider?.let { requireActivity().removeMenuProvider(it) }
        photoDetailMenuProvider = null
        super.onDestroyView()
    }


}