package com.swifstagrime.feature_home.ui.fragments.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel : ViewModel() {
    private val _hasAnimationBeenPlayed = MutableStateFlow(false)
    val hasAnimationBeenPlayed: StateFlow<Boolean> = _hasAnimationBeenPlayed.asStateFlow()

    fun markAnimationAsPlayed() {
        if (!_hasAnimationBeenPlayed.value) {
            _hasAnimationBeenPlayed.value = true
        }
    }
}