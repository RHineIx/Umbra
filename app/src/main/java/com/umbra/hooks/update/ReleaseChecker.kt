package com.umbra.hooks.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

/** Public release metadata only; no account information or device identifiers. */
internal object ReleaseChecker {
    private const val ENDPOINT = "https://api.github.com/repos/RHineIx/Umbra/releases/latest"
    private const val MAX_RESPONSE = 512 * 1024
    data class Result(val update: AppUpdate?)

    // A null result is a failed check, not confirmation of being up to date.
    fun check(currentVersion: String): Result? {
        return try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Umbra")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE) throw IOException("Release response too large")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val release = JSONObject(body)
                val update = UpdateRules.available(currentVersion, release.getString("tag_name"),
                    release.getBoolean("draft"), release.getBoolean("prerelease"))
                val assets = release.optJSONArray("assets")
                val urls = if (update == null || assets == null) emptyList() else
                    (0 until assets.length()).mapNotNull { index ->
                        val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                        UpdateRules.downloadUrl(update.tag, asset.optString("name"), asset.optString("browser_download_url"))
                            ?.takeIf { asset.optString("state") == "uploaded" && asset.optLong("size") > 0 }
                    }.distinct()
                Result(update?.copy(downloadUrl = urls.singleOrNull()))
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
