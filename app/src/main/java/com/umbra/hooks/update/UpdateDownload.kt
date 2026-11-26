package com.umbra.hooks.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment

/** Called only by Umbra's authenticated, serialized update coordinator. */
internal object UpdateDownload {
    @Synchronized fun enqueue(context: Context, update: AppUpdate): String {
        val name = UpdateRules.apkName(update.tag)
        val url = UpdateRules.downloadUrl(update.tag, name, update.downloadUrl.orEmpty())
            ?: return "release_unavailable"
        // Legacy public storage needs permissions we deliberately do not request.
        if (Build.VERSION.SDK_INT < 29) return "browser_required"
        return runCatching {
            val manager = context.getSystemService(DownloadManager::class.java)
                ?: return "download_unavailable"
            val prefs = context.getSharedPreferences("umbra_downloads", Context.MODE_PRIVATE)
            val id = prefs.getLong(update.tag, -1L)
            if (id >= 0) {
                val existing = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    else null
                }
                if (existing == DownloadManager.STATUS_PENDING || existing == DownloadManager.STATUS_RUNNING ||
                    existing == DownloadManager.STATUS_PAUSED ||
                    (existing == DownloadManager.STATUS_SUCCESSFUL && manager.getUriForDownloadedFile(id) != null)) {
                    return "download_exists"
                }
            }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("Umbra update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val downloadId = manager.enqueue(request)
            prefs.edit().clear().putLong(update.tag, downloadId).apply()
            "download_started"
        }.getOrDefault("download_failed")
    }
}
