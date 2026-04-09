package com.umbra.hooks

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager

@SuppressLint("SetTextI18n")
class GboardActivity : AppCompatActivity() {

    private lateinit var tilLimit: TextInputLayout
    private lateinit var etLimit: EditText
    private lateinit var tilRetention: TextInputLayout
    private lateinit var etRetention: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gboard)

        tilLimit = findViewById(R.id.tilLimit)
        etLimit = findViewById(R.id.etLimit)
        tilRetention = findViewById(R.id.tilRetention)
        etRetention = findViewById(R.id.etRetention)
        val btnSave = findViewById<Button>(R.id.btnSave)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val service = MainActivity.xposedService

        val limitValue = PrefsManager.getInt(service, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT).toString()
        val retentionValue = PrefsManager.getInt(service, Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION).toString()

        tilLimit.isHintAnimationEnabled = false
        tilRetention.isHintAnimationEnabled = false

        etLimit.setText(limitValue)
        etRetention.setText(retentionValue)

        tilLimit.isHintAnimationEnabled = true
        tilRetention.isHintAnimationEnabled = true
    }

    private fun saveSettings() {
        val service = MainActivity.xposedService
        if (service == null) {
            Toast.makeText(this, "Xposed service disconnected", Toast.LENGTH_SHORT).show()
            return
        }

        val limit = etLimit.text.toString().toIntOrNull() ?: Constants.DEFAULT_GBOARD_LIMIT
        val retention = etRetention.text.toString().toIntOrNull() ?: Constants.DEFAULT_GBOARD_RETENTION

        val limitSaved = PrefsManager.putInt(service, Constants.KEY_GBOARD_LIMIT, limit)
        val retentionSaved = PrefsManager.putInt(service, Constants.KEY_GBOARD_RETENTION_DAYS, retention)

        if (limitSaved && retentionSaved) {
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            openAppInfo("com.google.android.inputmethod.latin")
        } else {
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }
}