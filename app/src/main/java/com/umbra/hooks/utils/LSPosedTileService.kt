package com.umbra.hooks.utils

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class LSPosedTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val command = arrayOf(
                "su",
                "-c",
                "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
            )
            Runtime.getRuntime().exec(command)
        } catch (_: Exception) {
        }
    }
}
