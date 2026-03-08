package com.nettarion.hyperborea.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.core.LinkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val licenseChecker: LicenseChecker,
) : ViewModel() {

    val codeInput = MutableStateFlow("")

    private val _linkingState = MutableStateFlow<LinkingState>(LinkingState.Idle)
    val linkingState: StateFlow<LinkingState> = _linkingState.asStateFlow()

    fun updateCode(code: String) {
        if (code.length <= 6 && code.all { it.isDigit() }) {
            codeInput.value = code
        }
    }

    fun submitCode() {
        val code = codeInput.value
        if (code.length != 6) return

        _linkingState.value = LinkingState.Linking
        viewModelScope.launch {
            when (val result = licenseChecker.linkWithCode(code)) {
                is LinkResult.Success -> _linkingState.value = LinkingState.Success
                is LinkResult.Error -> _linkingState.value = LinkingState.Error(result.message)
            }
        }
    }

    fun clearError() {
        _linkingState.value = LinkingState.Idle
    }
}

sealed interface LinkingState {
    data object Idle : LinkingState
    data object Linking : LinkingState
    data object Success : LinkingState
    data class Error(val message: String) : LinkingState
}
