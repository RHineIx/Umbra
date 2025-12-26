package com.umbra.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.umbra.hooks.utils.Constants

class GboardActivity : AppCompatActivity() {

    private lateinit var etClipLimit: TextInputEditText
    private lateinit var etRetentionDays: TextInputEditText
    private lateinit var btnSave: com.google.android.material.button.MaterialButton
    private lateinit var btnInfo: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gboard)

        etClipLimit = findViewById(R.id.etClipLimit)
        etRetentionDays = findViewById(R.id.etRetentionDays)
        btnSave = findViewById(R.id.btnSaveGboard)
        btnInfo = findViewById(R.id.btnGboardInfo)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettings()
        }

        btnInfo.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun loadSettings() {
        val dpContext = applicationContext.createDeviceProtectedStorageContext()
        val prefs = dpContext.getSharedPreferences(
            Constants.PREFS_FILE,
            Context.MODE_WORLD_READABLE
        )

        etClipLimit.setText(
            prefs.getInt(
                Constants.KEY_GBOARD_LIMIT,
                Constants.DEFAULT_GBOARD_LIMIT
            ).toString()
        )

        etRetentionDays.setText(
            prefs.getInt(
                Constants.KEY_GBOARD_RETENTION_DAYS,
                Constants.DEFAULT_GBOARD_RETENTION
            ).toString()
        )
    }

    @SuppressLint("WorldReadableFiles")
    private fun saveSettings() {
        val dpContext = applicationContext.createDeviceProtectedStorageContext()

        val limit = etClipLimit.text?.toString()?.toIntOrNull()
            ?: Constants.DEFAULT_GBOARD_LIMIT

        val days = etRetentionDays.text?.toString()?.toIntOrNull()
            ?: Constants.DEFAULT_GBOARD_RETENTION

        val prefs = dpContext.getSharedPreferences(
            Constants.PREFS_FILE,
            Context.MODE_WORLD_READABLE
        )

        prefs.edit()
            .putInt(Constants.KEY_GBOARD_LIMIT, limit)
            .putInt(Constants.KEY_GBOARD_RETENTION_DAYS, days)
            .commit()

        Toast.makeText(this, "Saved. Restart Gboard.", Toast.LENGTH_SHORT).show()
        openGboardAppInfo()
    }

    private fun openGboardAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.inputmethod.latin")
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun showAboutDialog() {
        val message =
            "This Gboard clipboard hook is inspired by and partially based on the open-source project:\n\n" +
            "GboardHook by chenyue404"

        MaterialAlertDialogBuilder(this)
            .setTitle("About Gboard Hook")
            .setMessage(message)
            .setPositiveButton("Open GitHub") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/chenyue404/GboardHook")
                        )
                    )
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}