package com.umbra.hooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        
        if (service != null) {
            _uiState.update { 
                it.copy(
                    isServiceActive = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion
                )
            }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val logs = PrefsManager.getInt(service, Constants.KEY_GLOBAL_LOGS, 0) == 1
                    _uiState.update { it.copy(logsEnabled = logs) }
                } catch (_: Throwable) {
                }
            }
        } else {
            _uiState.update { it.copy(isServiceActive = false) }
        }
    }

    fun toggleLogs(enabled: Boolean) {
        val service = xposedService ?: return
        
        _uiState.update { it.copy(logsEnabled = enabled) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PrefsManager.putInt(service, Constants.KEY_GLOBAL_LOGS, if (enabled) 1 else 0)
            } catch (_: Throwable) {
            }
        }
    }
}