package com.umbra.hooks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.umbra.hooks.utils.Constants

class PydroidActivity : AppCompatActivity() {

    private lateinit var switchPremium: MaterialSwitch
    private lateinit var switchNoAds: MaterialSwitch
    private lateinit var switchNoJump: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pydroid)

        switchPremium = findViewById(R.id.switchPremium)
        switchNoAds = findViewById(R.id.switchNoAds)
        switchNoJump = findViewById(R.id.switchNoJump)
        val btnSave = findViewById<MaterialButton>(R.id.btnSavePydroid)
        val imgInfo = findViewById<ImageView>(R.id.imgPydroidInfo)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettings()
        }

        imgInfo.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Pydroid Hook Info")
                .setMessage("Toggle the features you need. \n\nNote: You must Force Stop 'Pydroid 3' after saving for changes to take effect.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun loadSettings() {
        @Suppress("DEPRECATION")
        val prefs = getSharedPreferences(Constants.PREFS_FILE, Context.MODE_WORLD_READABLE)

        switchPremium.isChecked = prefs.getBoolean(Constants.KEY_PYDROID_PREMIUM, true)
        switchNoAds.isChecked = prefs.getBoolean(Constants.KEY_PYDROID_NO_ADS, true)
        switchNoJump.isChecked = prefs.getBoolean(Constants.KEY_PYDROID_NO_JUMP, true)
    }

    private fun saveSettings() {
        @Suppress("DEPRECATION")
        val prefs = getSharedPreferences(Constants.PREFS_FILE, Context.MODE_WORLD_READABLE)
        
        prefs.edit().apply {
            putBoolean(Constants.KEY_PYDROID_PREMIUM, switchPremium.isChecked)
            putBoolean(Constants.KEY_PYDROID_NO_ADS, switchNoAds.isChecked)
            putBoolean(Constants.KEY_PYDROID_NO_JUMP, switchNoJump.isChecked)
            apply()
        }

        Toast.makeText(this, "Saved! Please Force Stop Pydroid 3.", Toast.LENGTH_LONG).show()
        openAppInfo("ru.iiec.pydroid3")
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // فشل فتح الإعدادات
        }
    }
}
