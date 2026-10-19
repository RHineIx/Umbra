package com.umbra.hooks

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.hooks.ui.MainViewModel
import com.umbra.hooks.ui.UmbraScreen
import com.umbra.hooks.ui.UmbraTheme
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set
        private var isListenerRegistered = false
        private var activityRef = WeakReference<MainActivity>(null)
        private val serviceListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                activityRef.get()?.let { activity ->
                    activity.runOnUiThread { activity.viewModel.updateServiceStatus(service) }
                }
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
                activityRef.get()?.let { activity ->
                    activity.runOnUiThread { activity.viewModel.updateServiceStatus(null) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectService()
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "Unknown"
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            UmbraTheme {
                UmbraScreen(
                    state = state,
                    version = version,
                    onLogsChanged = viewModel::toggleLogs,
                    onHideIconChanged = ::toggleLauncherIcon,
                    onDeveloperClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineIx"))) }
                            .onFailure { toast(R.string.toast_link_unavailable) }
                    },
                    readClipboardSettings = {
                        val service = xposedService
                        if (service == null) {
                            toast(R.string.toast_service_disconnected)
                            null
                        } else {
                            PrefsManager.getInt(service, Constants.KEY_GBOARD_CLIPBOARD_LIMIT, Constants.DEFAULT_GBOARD_CLIPBOARD_LIMIT) to
                                PrefsManager.getInt(service, Constants.KEY_GBOARD_CLIPBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_CLIPBOARD_RETENTION_DAYS)
                        }
                    },
                    saveClipboardSettings = { limit, days ->
                        val saved = PrefsManager.putGboardClipboardSettings(xposedService, limit, days)
                        if (saved) {
                            toast(R.string.toast_gboard_saved_force_stop)
                            runCatching {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", "com.google.android.inputmethod.latin", null)))
                            }.onFailure { toast(R.string.toast_gboard_settings_unavailable) }
                        } else toast(R.string.toast_settings_failed)
                        saved
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        connectService()
    }

    private fun connectService() {
        activityRef = WeakReference(this)
        if (!isListenerRegistered) {
            runCatching {
                XposedServiceHelper.registerListener(serviceListener)
                isListenerRegistered = true
            }
        }
        viewModel.checkLauncherIconState(this)
        viewModel.updateServiceStatus(xposedService)
    }

    override fun onDestroy() {
        if (activityRef.get() === this) activityRef.clear()
        super.onDestroy()
    }

    private fun toggleLauncherIcon(hide: Boolean) {
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.MainActivityAlias"),
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            viewModel.updateIconState(hide)
            if (hide) toast(R.string.toast_icon_hidden)
        }.onFailure { toast(R.string.toast_settings_failed) }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
