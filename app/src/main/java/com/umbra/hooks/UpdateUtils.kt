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
}