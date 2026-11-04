package com.dssmeet.kiosk.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.models.admin.Prisoner
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AdminRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InmateDetailsViewModel @Inject constructor(
    private val adminRepository: AdminRepository
) : ViewModel() {

    private val _prisoner = MutableStateFlow<NetworkResult<Prisoner>>(NetworkResult.Idle)
    val prisoner: StateFlow<NetworkResult<Prisoner>> = _prisoner.asStateFlow()

    fun loadPrisoner(prisonerId: String) {
        viewModelScope.launch {
            adminRepository.getPrisoner(prisonerId).collect { result ->
                _prisoner.value = result
            }
        }
    }
}
