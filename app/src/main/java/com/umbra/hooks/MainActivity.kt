package com.umbra.hooks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.umbra.hooks.ui.HookRegistry
import com.umbra.hooks.ui.HooksAdapter
import com.umbra.hooks.ui.MainViewModel
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var swGlobalLogs: MaterialSwitch
    private lateinit var swHideIcon: MaterialSwitch
    private var currentActiveState: Boolean? = null

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private var isListenerRegistered = false
        private var activityRef = WeakReference<MainActivity>(null)

        private val serviceListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                activityRef.get()?.viewModel?.updateServiceStatus(service)
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                activityRef.get()?.viewModel?.updateServiceStatus(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        activityRef = WeakReference(this)
        registerXposedService()

        viewModel.checkLauncherIconState(this)
        viewModel.updateServiceStatus(xposedService)

        initViews()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        activityRef = WeakReference(this)
        registerXposedService()
        viewModel.updateServiceStatus(xposedService)
        // Ensure the launcher icon switch accurately reflects system state when returning to the app
        viewModel.checkLauncherIconState(this)
    }

    private fun registerXposedService() {
        if (!isListenerRegistered) {
            try {
                XposedServiceHelper.registerListener(serviceListener)
                isListenerRegistered = true
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            activityRef.clear()
            xposedService = null
            exitProcess(0)
        }
    }

    private fun initViews() {
        val mainContainer = findViewById<ViewGroup>(R.id.mainContainer)
        
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padding24px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
            
            view.setPadding(
                systemBars.left + padding24px,
                systemBars.top + padding24px,
                systemBars.right + padding24px,
                systemBars.bottom + padding24px
            )
            insets
        }

        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val layoutExpandedInfo = findViewById<View>(R.id.layoutExpandedInfo)
        val layoutSettingsWrapper = findViewById<FrameLayout>(R.id.layoutSettingsWrapper)
        val cardSettings = findViewById<MaterialCardView>(R.id.cardSettings)
        
        val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
        val tvDeveloperInfo = findViewById<TextView>(R.id.tvDeveloperInfo)

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvAppVersion.text = getString(R.string.app_version_format, pInfo.versionName)
        } catch (_: Exception) {
            tvAppVersion.text = getString(R.string.app_version_format, "Unknown")
        }
        tvDeveloperInfo.text = getString(R.string.developer_info)

        tvDeveloperInfo.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineIx"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
            }
        }

        var isExpanded = false
        var expandAnimator: ValueAnimator? = null
        var currentFraction = 0f
        var targetInfoHeight = 0
        var targetSettingsHeight = 0

        cardStatus.setOnClickListener {
            expandAnimator?.cancel()

            if (layoutExpandedInfo.visibility == View.GONE) {
                layoutExpandedInfo.visibility = View.INVISIBLE
                layoutSettingsWrapper.visibility = View.INVISIBLE
            }

            val widthSpec = View.MeasureSpec.makeMeasureSpec(cardStatus.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            val lpInfo = layoutExpandedInfo.layoutParams
            val originalInfoHeight = lpInfo.height
            lpInfo.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutExpandedInfo.layoutParams = lpInfo
            layoutExpandedInfo.measure(widthSpec, heightSpec)
            targetInfoHeight = layoutExpandedInfo.measuredHeight
            lpInfo.height = originalInfoHeight
            layoutExpandedInfo.layoutParams = lpInfo

            val lpSettings = layoutSettingsWrapper.layoutParams
            val originalSettingsHeight = lpSettings.height
            lpSettings.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutSettingsWrapper.layoutParams = lpSettings
            layoutSettingsWrapper.measure(widthSpec, heightSpec)
            targetSettingsHeight = layoutSettingsWrapper.measuredHeight
            lpSettings.height = originalSettingsHeight
            layoutSettingsWrapper.layoutParams = lpSettings

            layoutExpandedInfo.visibility = View.VISIBLE
            layoutSettingsWrapper.visibility = View.VISIBLE

            val startFraction = currentFraction
            val targetFraction = if (isExpanded) 0f else 1f
            isExpanded = !isExpanded

            expandAnimator = ValueAnimator.ofFloat(startFraction, targetFraction).apply {
                duration = (400 * abs(targetFraction - startFraction)).toLong().coerceAtLeast(50L)
                interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        layoutExpandedInfo.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        layoutSettingsWrapper.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        cardSettings.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        layoutExpandedInfo.setLayerType(View.LAYER_TYPE_NONE, null)
                        layoutSettingsWrapper.setLayerType(View.LAYER_TYPE_NONE, null)
                        cardSettings.setLayerType(View.LAYER_TYPE_NONE, null)

                        if (currentFraction == 0f) {
                            layoutExpandedInfo.visibility = View.GONE
                            layoutSettingsWrapper.visibility = View.GONE
                        } else if (currentFraction == 1f) {
                            layoutExpandedInfo.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            layoutSettingsWrapper.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    }
                })

                addUpdateListener { animation ->
                    currentFraction = animation.animatedValue as Float
                    layoutExpandedInfo.layoutParams.height = (targetInfoHeight * currentFraction).toInt()
                    layoutExpandedInfo.requestLayout()

                    layoutSettingsWrapper.layoutParams.height = (targetSettingsHeight * currentFraction).toInt()
                    layoutSettingsWrapper.requestLayout()

                    cardSettings.translationY = -targetSettingsHeight * (1f - currentFraction)
                    cardSettings.alpha = 0.3f + (0.7f * currentFraction)
                }
                start()
            }
        }

        findViewById<ImageView>(R.id.imgLsposed).setOnClickListener {
            openLSPosedManager()
        }

        swGlobalLogs = findViewById(R.id.swGlobalLogs)
        swGlobalLogs.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLogs(isChecked)
        }

        swHideIcon = findViewById(R.id.swHideIcon)
        swHideIcon.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                toggleLauncherIcon(isChecked)
                viewModel.updateIconState(isChecked)
            }
        }

        // Initialize dynamic RecyclerView for Hooks
        val rvHooks = findViewById<RecyclerView>(R.id.rvHooks)
        rvHooks.layoutManager = LinearLayoutManager(this)
        rvHooks.adapter = HooksAdapter(HookRegistry.getHooks())
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(
                        isActive = state.isServiceActive,
                        fwName = state.frameworkName,
                        fwVersion = state.frameworkVersion,
                        fwVersionCode = state.frameworkVersionCode,
                        fwApi = state.frameworkApi
                    )
                    
                    if (swGlobalLogs.isChecked != state.logsEnabled) {
                        swGlobalLogs.isChecked = state.logsEnabled
                    }
                    
                    if (swHideIcon.isChecked != state.isIconHidden) {
                        swHideIcon.isChecked = state.isIconHidden
                    }
                }
            }
        }
    }

    private fun toggleLauncherIcon(hide: Boolean) {
        val componentName = ComponentName(this, "com.umbra.hooks.MainActivityAlias")
        val state = if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        
        packageManager.setComponentEnabledSetting(
            componentName,
            state,
            PackageManager.DONT_KILL_APP
        )
        
        if (hide) {
            Toast.makeText(this, R.string.toast_icon_hidden, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI(isActive: Boolean, fwName: String?, fwVersion: String?, fwVersionCode: Long?, fwApi: Int?) {
        if (currentActiveState == isActive) return
        val isFirstLoad = currentActiveState == null
        currentActiveState = isActive

        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)
        val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
        val tvDeveloperInfo = findViewById<TextView>(R.id.tvDeveloperInfo)
        val imgWatermark = findViewById<ImageView>(R.id.imgStatusWatermark)

        val targetBg = if (isActive) getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer) 
                       else getThemeColor(com.google.android.material.R.attr.colorErrorContainer)
        val targetContent = if (isActive) getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
                            else getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer)

        tvTitle.text = if (isActive) getString(R.string.title_module_activated) else getString(R.string.title_module_inactive)
        
        val name = fwName ?: "Unknown"
        val version = fwVersion ?: "Unknown"
        val vCode = if (fwVersionCode != null && fwVersionCode > 0) fwVersionCode.toString() else "Unknown"
        val api = if (fwApi != null && fwApi > 0) fwApi.toString() else "Unknown"
        
        tvDesc.text = if (isActive) getString(R.string.desc_module_running_format, name, version, vCode, api) 
                      else getString(R.string.desc_module_enable_reboot)
                      
        imgWatermark.isActivated = isActive

        if (isFirstLoad) {
            cardStatus.setCardBackgroundColor(targetBg)
            tvTitle.setTextColor(targetContent)
            tvDesc.setTextColor(targetContent)
            tvAppVersion.setTextColor(targetContent)
            tvDeveloperInfo.setTextColor(targetContent)
            imgWatermark.imageTintList = ColorStateList.valueOf(targetContent)
        } else {
            animateColorChange(cardStatus, tvTitle, tvDesc, tvAppVersion, tvDeveloperInfo, imgWatermark, targetBg, targetContent)
        }
    }

    private fun animateColorChange(
        card: MaterialCardView, title: TextView, desc: TextView, 
        version: TextView, dev: TextView, img: ImageView, bg: Int, content: Int
    ) {
        ValueAnimator.ofObject(ArgbEvaluator(), card.cardBackgroundColor.defaultColor, bg).apply {
            duration = 250
            addUpdateListener { card.setCardBackgroundColor(it.animatedValue as Int) }
            start()
        }
        ValueAnimator.ofObject(ArgbEvaluator(), title.currentTextColor, content).apply {
            duration = 250
            addUpdateListener {
                val c = it.animatedValue as Int
                title.setTextColor(c)
                desc.setTextColor(c)
                version.setTextColor(c)
                dev.setTextColor(c)
                img.imageTintList = ColorStateList.valueOf(c)
            }
            start()
        }
    }

    private fun openLSPosedManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"))
                if (process.waitFor() != 0) withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, R.string.toast_root_denied, Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, R.string.toast_device_not_rooted, Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }
}