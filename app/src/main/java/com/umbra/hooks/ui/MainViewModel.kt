package com.umbra.hooks.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.umbra.hooks.update.AppUpdate
import com.umbra.hooks.update.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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
        val frameworkVersionCode: Long? = null,
        val logsEnabled: Boolean = false,
        val isIconHidden: Boolean = false,
        val availableUpdate: AppUpdate? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var xposedService: XposedService? = null
    private var updateJob: Job? = null
    private var nextUpdateCheckAt = 0L

    // Called only by the module's own Activity. No polling or network work in
    // hooked processes. Throttle repeat resumes and retry failures quietly.
    fun checkForUpdate(context: Context) {
        if (updateJob?.isActive == true || SystemClock.elapsedRealtime() < nextUpdateCheckAt) return
        updateJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateRepository.check(context.applicationContext) }.getOrNull() }
            nextUpdateCheckAt = SystemClock.elapsedRealtime() +
                if (result == null) 60_000L else 6 * 60 * 60 * 1000L
            if (result != null) _uiState.update { it.copy(availableUpdate = result.update) }
        }
    }


    // Read the current physical state of the launcher icon from the Android system
    fun checkLauncherIconState(context: Context) {
        val componentName = ComponentName(context, "com.umbra.hooks.MainActivityAlias")
        val isHidden = context.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        _uiState.update { it.copy(isIconHidden = isHidden) }
    }

    // Force update the UI state immediately to prevent visual desync before the OS propagates the change
    fun updateIconState(isHidden: Boolean) {
        _uiState.update { it.copy(isIconHidden = isHidden) }
    }

    fun updateServiceStatus(service: XposedService?) {
        xposedService = service
        
        if (service != null) {
            _uiState.update { 
                it.copy(
                    isServiceActive = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    frameworkVersionCode = service.frameworkVersionCode
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