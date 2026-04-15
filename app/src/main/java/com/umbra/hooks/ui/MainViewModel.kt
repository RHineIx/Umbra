package com.umbra.hooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel to handle UI state and Xposed Service interaction.
 * Follows Clean Architecture principles by decoupling logic from MainActivity.
 */
class MainViewModel : ViewModel() {

    data class UiState(
        val isServiceActive: Boolean = false,
        val frameworkName: String? = null,
        val frameworkVersion: String? = null,
        val logsEnabled: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var xposedService: XposedService? = null

    fun updateServiceStatus(service: XposedService?) {
        xposedService = service
        viewModelScope.launch {
            if (service != null) {
                val logs = PrefsManager.getInt(service, Constants.KEY_GLOBAL_LOGS, 0) == 1
                _uiState.value = UiState(
                    isServiceActive = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    logsEnabled = logs
                )
            } else {
                _uiState.value = UiState(isServiceActive = false)
            }
        }
    }

    fun toggleLogs(enabled: Boolean) {
        val service = xposedService ?: return
        viewModelScope.launch {
            val success = PrefsManager.putInt(service, Constants.KEY_GLOBAL_LOGS, if (enabled) 1 else 0)
            if (success) {
                _uiState.value = _uiState.value.copy(logsEnabled = enabled)
            }
        }
    }
}