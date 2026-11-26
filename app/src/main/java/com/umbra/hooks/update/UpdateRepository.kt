package com.umbra.hooks.update

import android.content.Context

/** One persistent cache and notification record in Umbra's own process. */
internal object UpdateRepository {
    private const val SUCCESS_TTL = 6 * 60 * 60 * 1000L
    private const val FAILURE_TTL = 60_000L
    private const val PREFS = "umbra_updates"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"

    // Only call on a worker or provider binder thread. Serialize refreshes
    // across the module UI and all authenticated clients.
    @Synchronized fun check(context: Context): ReleaseChecker.Result? {
        val version = installedVersion(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val sameVersion = prefs.getString("installed", null) == version
        val age = now - prefs.getLong("checked", 0L)
        if (sameVersion && prefs.getInt("schema", 0) == 2 && age in 0 until SUCCESS_TTL) {
            val tag = prefs.getString("tag", null)
            val update = tag?.let { UpdateRules.available(version, it, false, false) }
                ?.let { candidate ->
                    candidate.copy(downloadUrl = UpdateRules.downloadUrl(candidate.tag,
                        UpdateRules.apkName(candidate.tag), prefs.getString("apk_url", "").orEmpty()))
                }
            return ReleaseChecker.Result(update)
        }
        val retryAge = now - prefs.getLong("attempt", 0L)
        if (sameVersion && prefs.getInt("schema", 0) == 2 && retryAge in 0 until FAILURE_TTL) return null
        val result = ReleaseChecker.check(version)
        val editor = prefs.edit().putString("installed", version)
            .putLong("attempt", System.currentTimeMillis()).putInt("schema", 2).remove("notes")
        if (!sameVersion) editor.remove("checked").remove("tag").remove("apk_url")
        if (result != null) {
            editor.putLong("checked", System.currentTimeMillis())
                .putString("tag", result.update?.tag)
                .putString("apk_url", result.update?.downloadUrl)
        }
        editor.apply()
        return result
    }

    @Synchronized fun wasShown(context: Context, tag: String): Boolean =
        prefs(context).getString("shown", null) == tag

    @Synchronized fun markShown(context: Context, tag: String): Boolean =
        prefs(context).edit().putString("shown", tag).commit()
}
