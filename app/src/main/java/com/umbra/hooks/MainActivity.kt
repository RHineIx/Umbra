package com.umbra.hooks

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var swGlobalLogs: MaterialSwitch

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private var isListenerRegistered = false

        @SuppressLint("StaticFieldLeak")
        private var currentActivity: MainActivity? = null

        private val globalServiceListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                currentActivity?.runOnUiThread {
                    currentActivity?.updateUI(true, service.frameworkName, service.frameworkVersion)
                }
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                currentActivity?.runOnUiThread {
                    currentActivity?.updateUI(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentActivity = this

        if (!isListenerRegistered) {
            try {
                XposedServiceHelper.registerListener(globalServiceListener)
                isListenerRegistered = true
            } catch (_: Throwable) {}
        }

        findViewById<MaterialCardView>(R.id.cardGboard).setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }

        findViewById<ImageView>(R.id.imgLsposed).setOnClickListener {
            openLSPosedManager()
        }

        swGlobalLogs = findViewById(R.id.swGlobalLogs)
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this

        val service = xposedService
        if (service != null) {
            updateUI(true, service.frameworkName, service.frameworkVersion)
        } else {
            updateUI(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (currentActivity == this) {
            currentActivity = null
        }

        if (isFinishing) {
            if (isListenerRegistered) {
                try {
                    isListenerRegistered = false
                    xposedService = null
                } catch (_: Throwable) {}
            }

            exitProcess(0)
        }
    }

    private fun openLSPosedManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val command = arrayOf(
                    "su",
                    "-c",
                    "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
                )
                val process = Runtime.getRuntime().exec(command)
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Root permission denied.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Device is not rooted.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupGlobalLogs() {
        val isEnabled = PrefsManager.getInt(xposedService, Constants.KEY_GLOBAL_LOGS, 0) == 1
        swGlobalLogs.setOnCheckedChangeListener(null)
        swGlobalLogs.isChecked = isEnabled

        swGlobalLogs.setOnCheckedChangeListener { _, isChecked ->
            val success = PrefsManager.putInt(xposedService, Constants.KEY_GLOBAL_LOGS, if (isChecked) 1 else 0)
            if (!success && isChecked) {
                swGlobalLogs.isChecked = false
                Toast.makeText(this, "Xposed service disconnected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI(isActive: Boolean, fwName: String? = null, fwVersion: String? = null) {
        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val imgStatus = findViewById<ImageView>(R.id.imgStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)

        setupGlobalLogs()

        if (isActive) {
            val colorContainer = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            val colorOnContainer = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)

            cardStatus.setCardBackgroundColor(colorContainer)
            imgStatus.imageTintList = ColorStateList.valueOf(colorOnContainer)
            tvTitle.setTextColor(colorOnContainer)
            tvDesc.setTextColor(colorOnContainer)

            tvTitle.text = "Module Activated"
            tvDesc.text = "Running in ${fwName ?: "Unknown"} (${fwVersion ?: "Unknown"})"
        } else {
            val colorContainer = getThemeColor(com.google.android.material.R.attr.colorErrorContainer)
            val colorOnContainer = getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer)

            cardStatus.setCardBackgroundColor(colorContainer)
            imgStatus.imageTintList = ColorStateList.valueOf(colorOnContainer)
            tvTitle.setTextColor(colorOnContainer)
            tvDesc.setTextColor(colorOnContainer)

            tvTitle.text = "Module Not Active"
            tvDesc.text = "Enable in LSPosed and reboot."
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }
}