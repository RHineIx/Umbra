package com.umbra.hooks.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateUtils {
    private const val REPO_URL = "https://api.github.com/repos/RHineIx/Umbra/releases/latest"

    suspend fun checkUpdate(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(REPO_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                return@withContext json.optString("tag_name", null)
            }
        } catch (_: Exception) {}
        null
    }

    // دالة المقارنة الذكية
    fun isNewer(current: String, latest: String): Boolean {
        // تنظيف النصوص من الحروف والمسافات والإبقاء على الأرقام والنقاط فقط
        val cleanCurrent = current.replace(Regex("[^0-9.]"), "")
        val cleanLatest = latest.replace(Regex("[^0-9.]"), "")

        val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }

        val length = maxOf(currentParts.size, latestParts.size)

        for (i in 0 until length) {
            val v1 = if (i < currentParts.size) currentParts[i] else 0
            val v2 = if (i < latestParts.size) latestParts[i] else 0

            if (v2 > v1) return true  // التحديث أحدث
            if (v2 < v1) return false // الإصدار الحالي أحدث أو يساويه
        }
        return false // الإصداران متطابقان تماماً
    }
}