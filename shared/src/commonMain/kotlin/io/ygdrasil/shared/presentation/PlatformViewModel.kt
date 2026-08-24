package io.ygdrasil.shared.presentation

import io.ygdrasil.shared.domain.model.PlatformInfo
import io.ygdrasil.shared.domain.usecase.GetPlatformInfoUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlatformUiState {
    data object Loading : PlatformUiState
    data class Success(val info: PlatformInfo) : PlatformUiState
    data class Error(val message: String) : PlatformUiState
}

class PlatformViewModel(
    private val getPlatformInfoUseCase: GetPlatformInfoUseCase,
    private val viewModelScope: CoroutineScope
) {
    private val _uiState = MutableStateFlow<PlatformUiState>(PlatformUiState.Loading)
    val uiState: StateFlow<PlatformUiState> = _uiState.asStateFlow()

    init {
        loadPlatformInfo()
    }

    fun loadPlatformInfo() {
        viewModelScope.launch {
            _uiState.value = PlatformUiState.Loading
            getPlatformInfoUseCase().collect { result ->
                result.fold(
                    onSuccess = { info ->
                        _uiState.value = PlatformUiState.Success(info)
                    },
                    onFailure = { error ->
                        _uiState.value = PlatformUiState.Error(error.message ?: "Une erreur inconnue est survenue")
                    }
                )
            }
        }
    }
}
