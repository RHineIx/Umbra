package com.umbra.hooks

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialCardView>(R.id.cardGboard).setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }

        findViewById<ImageView>(R.id.imgAbout).setOnClickListener {
            showAboutDialog()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun showAboutDialog() {
        val aboutText = """
            <b>Umbra v3.0</b><br><br>
            simple module<br><br>
            <b>Developer: </b>
            <a href="https://t.me/RHineix">@RHineIx</a>
        """.trimIndent()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("About Umbra")
            .setMessage(Html.fromHtml(aboutText, Html.FROM_HTML_MODE_COMPACT))
            .setCancelable(true)
            .create()

        dialog.show()

        (dialog.findViewById<TextView>(android.R.id.message))?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun updateUI() {
        val isModuleActive = isModuleActive()
        updateGlobalStatus(isModuleActive)
    }

    private fun updateGlobalStatus(isActive: Boolean) {
        val cardStatus = findViewById<MaterialCardView>(R.id.cardStatus)
        val imgStatus = findViewById<ImageView>(R.id.imgStatus)
        val tvTitle = findViewById<TextView>(R.id.tvStatusTitle)
        val tvDesc = findViewById<TextView>(R.id.tvStatusDesc)

        if (isActive) {
            val colorContainer =
                getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            val colorOnContainer =
                getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)

            cardStatus.setCardBackgroundColor(colorContainer)
            imgStatus.imageTintList = ColorStateList.valueOf(colorOnContainer)
            tvTitle.setTextColor(colorOnContainer)
            tvDesc.setTextColor(colorOnContainer)
            tvTitle.text = "Module Activated"
            tvDesc.text = "Umbra is running properly."
        } else {
            val colorContainer =
                getThemeColor(com.google.android.material.R.attr.colorErrorContainer)
            val colorOnContainer =
                getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer)

            cardStatus.setCardBackgroundColor(colorContainer)
            imgStatus.imageTintList = ColorStateList.valueOf(colorOnContainer)
            tvTitle.setTextColor(colorOnContainer)
            tvDesc.setTextColor(colorOnContainer)
            tvTitle.text = "Module Not Active"
            tvDesc.text = "Enable in LSPosed and reboot."
        }
    }

    // Hooked by MainHook when the module is active
    private fun isModuleActive(): Boolean {
        return false
    }

    private fun getThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }
}