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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.umbra.hooks.ui.MainViewModel
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var swGlobalLogs: MaterialSwitch
    private var currentActiveState: Boolean? = null

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private var isListenerRegistered = false

        @SuppressLint("StaticFieldLeak")
        private var currentActivity: MainActivity? = null
    }

    private val globalServiceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService = service
            viewModel.updateServiceStatus(service)
        }

        override fun onServiceDied(service: XposedService) {
            xposedService = null
            viewModel.updateServiceStatus(null)
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

        initViews()
        observeState()
    }

    private fun initViews() {
        findViewById<MaterialCardView>(R.id.cardGboard).setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }

        findViewById<ImageView>(R.id.imgLsposed).setOnClickListener {
            openLSPosedManager()
        }

        findViewById<TextView>(R.id.tvDeveloperCredits).setOnClickListener {
            openTelegramProfile()
        }

        swGlobalLogs = findViewById(R.id.swGlobalLogs)
        swGlobalLogs.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLogs(isChecked)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state.isServiceActive, state.frameworkName, state.frameworkVersion)
                    if (swGlobalLogs.isChecked != state.logsEnabled) {
                        swGlobalLogs.isChecked = state.logsEnabled
                    }
                }
            }
        }
    }

    private fun openTelegramProfile() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineIx")))
        } catch (e: Exception) {
            Toast.makeText(this, "Telegram or Browser not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(isActive: Boolean, fwName: String?, fwVersion: String?) {
        if (currentActiveState == isActive) return
        val isFirstLoad = currentActiveState == null
        currentActiveState = isActive

        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)
        val imgWatermark = findViewById<ImageView>(R.id.imgStatusWatermark)

        val targetBg = if (isActive) getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer) 
                       else getThemeColor(com.google.android.material.R.attr.colorErrorContainer)
        val targetContent = if (isActive) getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
                            else getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer)

        tvTitle.text = if (isActive) "Module Activated" else "Module Not Active"
        tvDesc.text = if (isActive) "Running in $fwName ($fwVersion)" else "Enable in LSPosed and reboot."
        imgWatermark.isActivated = isActive

        if (isFirstLoad) {
            cardStatus.setCardBackgroundColor(targetBg)
            tvTitle.setTextColor(targetContent)
            tvDesc.setTextColor(targetContent)
            imgWatermark.imageTintList = ColorStateList.valueOf(targetContent)
        } else {
            animateColorChange(cardStatus, tvTitle, tvDesc, imgWatermark, targetBg, targetContent)
        }
    }

    private fun animateColorChange(card: MaterialCardView, title: TextView, desc: TextView, img: ImageView, bg: Int, content: Int) {
        ValueAnimator.ofObject(ArgbEvaluator(), card.cardBackgroundColor.defaultColor, bg).apply {
            duration = 400
            addUpdateListener { card.setCardBackgroundColor(it.animatedValue as Int) }
            start()
        }
        ValueAnimator.ofObject(ArgbEvaluator(), title.currentTextColor, content).apply {
            duration = 400
            addUpdateListener {
                val c = it.animatedValue as Int
                title.setTextColor(c)
                desc.setTextColor(c)
                img.imageTintList = ColorStateList.valueOf(c)
            }
            start()
        }
    }

    private fun openLSPosedManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"))
                if (process.waitFor() != 0) withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Root denied", Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Device not rooted", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentActivity == this) {
            currentActivity = null
        }
        if (isFinishing) exitProcess(0)
    }
}