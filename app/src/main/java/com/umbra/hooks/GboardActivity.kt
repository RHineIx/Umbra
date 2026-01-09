package com.umbra.hooks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.umbra.hooks.utils.Constants
import com.umbra.hooks.utils.PrefsManager

class GboardActivity : AppCompatActivity() {

    private lateinit var etLimit: EditText
    private lateinit var etRetention: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gboard)

        etLimit = findViewById(R.id.etLimit)
        etRetention = findViewById(R.id.etRetention)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val imgInfo = findViewById<ImageView>(R.id.imgGboardInfo)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettingsAndOpenAppInfo()
        }

        imgInfo.setOnClickListener {
            showGboardHookInfoDialog()
        }
    }

    private fun showGboardHookInfoDialog() {
        val infoText = """
            <b>Gboard Hook</b><br><br>
            Configure clipboard limits.<br><br>
            Credits: chenyue404 / Umbra Team.
        """.trimIndent()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("GboardHook Info")
            .setMessage(Html.fromHtml(infoText, Html.FROM_HTML_MODE_COMPACT))
            .setCancelable(true)
            .create()
        dialog.show()
        (dialog.findViewById<TextView>(android.R.id.message))?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun loadSettings() {
        etLimit.setText(
            PrefsManager.getLocalInt(this, Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT).toString()
        )
        etRetention.setText(
            PrefsManager.getLocalInt(this, Constants.KEY_GBOARD_RETENTION_DAYS, Constants.DEFAULT_GBOARD_RETENTION).toString()
        )
    }

    private fun saveSettingsAndOpenAppInfo() {
        try {
            val limit = etLimit.text.toString().toIntOrNull() ?: 50
            val retention = etRetention.text.toString().toIntOrNull() ?: 30

            PrefsManager.putInt(this, Constants.KEY_GBOARD_LIMIT, limit)
            PrefsManager.putInt(this, Constants.KEY_GBOARD_RETENTION_DAYS, retention)
            
            Toast.makeText(this, "Settings saved. Force stop Gboard.", Toast.LENGTH_SHORT).show()
            val imePkg = getCurrentImePackage() ?: "com.google.android.inputmethod.latin"
            openAppInfo(imePkg)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentImePackage(): String? {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return null
        return ime.substringBefore('/', ime)
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}