package com.swifstagrime.feature_home.ui.fragments.home

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.swifstagrime.core_ui.ui.fragments.BaseFragment
import com.swifstagrime.feature_home.databinding.FragmentHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentHomeBinding
        get() = FragmentHomeBinding::inflate

    private val viewModel: HomeViewModel by viewModels()
    private var typingAnimationJob: Job? = null
    private val typingDelayMs = 120L
    private var heightSet = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!viewModel.hasAnimationBeenPlayed.value) {
            prepareWelcomeTextForAnimation()
        }
    }

    override fun setupViews() {
        binding.settingsBtn.setOnClickListener {
            navigateToSettings()
        }

        binding.cameraShortcut.setOnClickListener {
            navigateToCamera()
        }

        binding.audioShortcut.setOnClickListener {
            navigateToAudioRecorder()
        }

        binding.docsShortcut.setOnClickListener {
            navigateToDocuments()
        }
    }

    private fun prepareWelcomeTextForAnimation() {
        val welcomeTextView = binding.welcomeText
        val finalFullText =
            getString(com.swifstagrime.core_ui.R.string.welcome_sign).replace(" ", "\n")
        welcomeTextView.text = finalFullText
        welcomeTextView.visibility = View.INVISIBLE
        heightSet = false

        welcomeTextView.doOnPreDraw {
            if (!heightSet && it.height > 0 && it.width > 0) {
                val measuredHeight = it.height

                val params = it.layoutParams
                params.height = measuredHeight
                it.layoutParams = params
                heightSet = true
                it.visibility = View.VISIBLE
                startWelcomeTextAnimation(finalFullText)
            }
        }
    }

    private fun startWelcomeTextAnimation(fullText: String) {
        typingAnimationJob?.cancel()
        val welcomeTextView = binding.welcomeText
        val actualTextColor = welcomeTextView.currentTextColor
        val spannable = SpannableStringBuilder(fullText)
        spannable.setSpan(
            ForegroundColorSpan(Color.TRANSPARENT),
            0,
            fullText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        welcomeTextView.text = spannable

        typingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                for (i in 0 until fullText.length) {
                    val colorSpan = ForegroundColorSpan(actualTextColor)
                    spannable.setSpan(
                        colorSpan,
                        0,
                        i + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    welcomeTextView.text = spannable
                    delay(typingDelayMs)
                }
                setFinalTextState(fullText, actualTextColor)
                viewModel.markAnimationAsPlayed()

            } catch (e: Exception) {
                setFinalTextState(fullText, actualTextColor)
                viewModel.markAnimationAsPlayed()
            }
        }
    }

    private fun setFinalTextState(fullText: String, color: Int) {
        val finalSpannable = SpannableStringBuilder(fullText)
        finalSpannable.setSpan(
            ForegroundColorSpan(color),
            0,
            fullText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.welcomeText.text = finalSpannable
    }

    private fun navigateToSettings() {
        findNavController().navigate("sigillum://app/settings".toUri())
    }

    private fun navigateToCamera() {
        findNavController().navigate("sigillum://app/camera".toUri())
    }

    private fun navigateToAudioRecorder() {
        findNavController().navigate("sigillum://app/recorder".toUri())
    }

    private fun navigateToDocuments() {
        findNavController().navigate("sigillum://app/documents".toUri())
    }


    override fun onDestroyView() {
        typingAnimationJob?.cancel()
        super.onDestroyView()
    }
}