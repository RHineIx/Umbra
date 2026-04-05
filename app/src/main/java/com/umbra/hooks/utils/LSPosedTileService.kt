package com.umbra.hooks.utils

import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@RequiresApi(Build.VERSION_CODES.N)
class LSPosedTileService : TileService() {

    override fun onClick() {
        super.onClick()
        
        thread {
            try {
                val command = arrayOf(
                    "su",
                    "-c",
                    "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
                )
                val process = Runtime.getRuntime().exec(command)
                
                process.waitFor()
            } catch (_: Exception) {
            } finally {
                exitProcess(0)
            }
        }
    }
}
