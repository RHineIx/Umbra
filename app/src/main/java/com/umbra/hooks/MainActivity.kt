package com.umbra.hooks

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
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
import kotlin.system.exitProcess

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var swGlobalLogs: MaterialSwitch
    private var currentActiveState: Boolean? = null

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

        // Setup About Button Click Listener
        findViewById<ImageView>(R.id.btnAbout).setOnClickListener {
            showAboutDialog()
        }

        swGlobalLogs = findViewById(R.id.swGlobalLogs)
    }

    /**
     * Constructs and displays a Material 3 About Dialog.
     */
    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage("Developed by @RHineIx\n\nUmbra is an LSPosed module designed to enhance and modify app behaviors.")
            .setPositiveButton("Telegram") { _, _ ->
                openTelegramProfile()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Safely opens the developer's Telegram profile via external Intent.
     */
    private fun openTelegramProfile() {
        try {
            val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineIx"))
            startActivity(telegramIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Telegram is not installed or no web browser found.", Toast.LENGTH_SHORT).show()
        }
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
        if (currentActiveState == isActive) return
        val isFirstLoad = currentActiveState == null
        currentActiveState = isActive

        setupGlobalLogs()

        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)

        val imgWatermark = findViewById<ImageView>(R.id.imgStatusWatermark)

        val activeBg = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val activeContent = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val inactiveBg = getThemeColor(com.google.android.material.R.attr.colorErrorContainer)
        val inactiveContent = getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer)

        val targetBg = if (isActive) activeBg else inactiveBg
        val targetContent = if (isActive) activeContent else inactiveContent

        tvTitle.text = if (isActive) "Module Activated" else "Module Not Active"
        tvDesc.text = if (isActive) "Running in ${fwName ?: "Unknown"} (${fwVersion ?: "Unknown"})" else "Enable in LSPosed and reboot."
        imgWatermark.isActivated = isActive

        if (isFirstLoad) {
            cardStatus.setCardBackgroundColor(targetBg)
            tvTitle.setTextColor(targetContent)
            tvDesc.setTextColor(targetContent)
            imgWatermark.imageTintList = ColorStateList.valueOf(targetContent)
        } else {
            val currentBg = cardStatus.cardBackgroundColor.defaultColor
            ValueAnimator.ofObject(ArgbEvaluator(), currentBg, targetBg).apply {
                duration = 400
                addUpdateListener { animator ->
                    cardStatus.setCardBackgroundColor(animator.animatedValue as Int)
                }
                start()
            }

            val currentContentColor = tvTitle.currentTextColor
            ValueAnimator.ofObject(ArgbEvaluator(), currentContentColor, targetContent).apply {
                duration = 400
                addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    tvTitle.setTextColor(color)
                    tvDesc.setTextColor(color)
                    imgWatermark.imageTintList = ColorStateList.valueOf(color)
                }
                start()
            }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }
}