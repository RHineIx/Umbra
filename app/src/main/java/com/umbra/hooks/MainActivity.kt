package com.umbra.hooks

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var swGlobalLogs: MaterialSwitch
    private var xposedService: XposedService? = null

    // Listen for connection to the Xposed framework
    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService = service
            if (!isDestroyed) {
                runOnUiThread {
                    updateUI(true, service.frameworkName, service.frameworkVersion)
                }
            }
        }

        override fun onServiceDied(service: XposedService) {
            xposedService = null
            if (!isDestroyed) {
                runOnUiThread {
                    updateUI(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Connect to LSPosed
        try {
            XposedServiceHelper.registerListener(serviceListener)
        } catch (e: Throwable) {
            // Ignore if framework is not available
        }

        // Card clicks
        findViewById<MaterialCardView>(R.id.cardGboard).setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }

        findViewById<ImageView>(R.id.imgLsposed).setOnClickListener {
            openLSPosedManager()
        }

        // Setup logs switch
        swGlobalLogs = findViewById(R.id.swGlobalLogs)
        setupGlobalLogs()

        // Set initial state
        updateUI(false)
    }

    private fun openLSPosedManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val command = arrayOf(
                    "su",
                    "-c",
                    "am start -n com.android.shell/.BugreportWarningActivity -a android.intent.action.MAIN -c org.lsposed.manager.LAUNCH_MANAGER"
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
        val isEnabled = PrefsManager.getLocalInt(this, Constants.KEY_GLOBAL_LOGS, 0) == 1
        swGlobalLogs.isChecked = isEnabled

        swGlobalLogs.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.putInt(this, Constants.KEY_GLOBAL_LOGS, if (isChecked) 1 else 0)
        }
    }

    private fun updateUI(isActive: Boolean, fwName: String? = null, fwVersion: String? = null) {
        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val imgStatus = findViewById<ImageView>(R.id.imgStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)

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