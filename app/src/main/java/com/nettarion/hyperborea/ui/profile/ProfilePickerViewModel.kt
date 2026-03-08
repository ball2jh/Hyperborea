package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectProfile(id: Long, onSelected: () -> Unit) {
        viewModelScope.launch {
            profileRepository.setActiveProfile(id)
            onSelected()
        }
    }
}
