package com.umbra.hooks

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.View
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
import com.umbra.hooks.utils.UpdateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var swGlobalLogs: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI Setup
        findViewById<MaterialCardView>(R.id.cardGboard).setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }

        findViewById<ImageView>(R.id.imgAbout).setOnClickListener {
            showAboutDialog()
        }

        findViewById<ImageView>(R.id.imgLsposed).setOnClickListener {
            openLSPosedManager()
        }

        findViewById<MaterialCardView>(R.id.cardUpdate).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RHineIx/Umbra/releases")))
        }

        findViewById<ImageView>(R.id.btnDismissUpdate).setOnClickListener {
            findViewById<MaterialCardView>(R.id.cardUpdate).visibility = View.GONE
        }

        // Global Logs Switch Logic
        swGlobalLogs = findViewById(R.id.swGlobalLogs)
        setupGlobalLogs()

        updateUI()
        checkForUpdates()
    }

    private fun openLSPosedManager() {
        // Execute the root command in the background to prevent UI freezing (ANR)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val command = arrayOf(
                    "su",
                    "-c",
                    "am start -n com.android.shell/.BugreportWarningActivity -a android.intent.action.MAIN -c org.lsposed.manager.LAUNCH_MANAGER"
                )
                val process = Runtime.getRuntime().exec(command)
                val exitCode = process.waitFor()
                
                // Exit code != 0 means root was denied or command failed
                if (exitCode != 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Root permission denied.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                // Exception means 'su' binary is not found (Not rooted)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Device is not rooted.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupGlobalLogs() {
        // Load state
        val isEnabled = PrefsManager.getLocalInt(this, Constants.KEY_GLOBAL_LOGS, 0) == 1
        swGlobalLogs.isChecked = isEnabled

        // Save on change
        swGlobalLogs.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.putInt(this, Constants.KEY_GLOBAL_LOGS, if (isChecked) 1 else 0)
        }
    }

    private fun checkForUpdates() {
        val currentCode = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toString()
            }
        } catch (_: Exception) { "0" }

        lifecycleScope.launch {
            val latestTag = UpdateUtils.checkUpdate()
            if (latestTag != null) {
                if (UpdateUtils.isNewer(currentCode, latestTag)) {
                    findViewById<TextView>(R.id.tvUpdateDesc).text = "New release $latestTag is ready for download."
                    findViewById<MaterialCardView>(R.id.cardUpdate).visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "Unknown" }

        val aboutText = """
            <b>Umbra v$versionName</b><br><br>
            Production Release<br><br>
            <b>Developer: </b>
            <a href="https://t.me/RHineix">@RHineIx</a>
        """.trimIndent()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("About Umbra")
            .setMessage(Html.fromHtml(aboutText, Html.FROM_HTML_MODE_COMPACT))
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun updateUI() {
        val isActive = isModuleActive()
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
            tvDesc.text = "Umbra is running properly."
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

    private fun isModuleActive(): Boolean = false

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
