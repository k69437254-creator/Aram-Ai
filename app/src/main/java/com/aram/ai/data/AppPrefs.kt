package com.aram.ai.data

import android.content.Context
import android.content.SharedPreferences

data class EngineStatus(
    val state: String,
    val message: String,
    val updatedAt: Long,
    val startedAt: Long
) {
    val isBusy: Boolean get() = state in setOf("STARTING", "LOADING_MODEL", "STARTING_SERVER", "STOPPING")
    val isReady: Boolean get() = state == "READY"
}

object AppPrefs {
    private const val FILE = "aram_ai_runtime"
    private const val KEY_STATE = "engine_state"
    private const val KEY_MESSAGE = "engine_message"
    private const val KEY_UPDATED = "engine_updated_at"
    private const val KEY_STARTED = "engine_started_at"
    private const val KEY_LAN = "allow_lan"
    private const val KEY_DOWNLOAD_ID = "model_download_id"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun engineStatus(context: Context): EngineStatus {
        val p = prefs(context)
        return EngineStatus(
            state = p.getString(KEY_STATE, "STOPPED") ?: "STOPPED",
            message = p.getString(KEY_MESSAGE, "المحرك متوقف") ?: "المحرك متوقف",
            updatedAt = p.getLong(KEY_UPDATED, 0L),
            startedAt = p.getLong(KEY_STARTED, 0L)
        )
    }

    fun setEngineState(context: Context, state: String, message: String) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_UPDATED, now)
        if (state == "READY") edit.putLong(KEY_STARTED, now)
        if (state == "STOPPED" || state == "ERROR") edit.putLong(KEY_STARTED, 0L)
        edit.apply()
    }

    /** Clear persisted busy/ready flags when Android has already destroyed the service process. */
    fun reconcileEngineState(context: Context, serviceAlive: Boolean) {
        if (serviceAlive) return
        val status = engineStatus(context)
        val staleBusy = status.isBusy && System.currentTimeMillis() - status.updatedAt > 10_000L
        val staleReady = status.state == "READY"
        if (staleBusy || staleReady) {
            setEngineState(context, "STOPPED", "خدمة المحرك غير نشطة؛ شغّلها مجددًا عند الحاجة.")
        }
    }

    fun allowLan(context: Context): Boolean = prefs(context).getBoolean(KEY_LAN, false)
    fun setAllowLan(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_LAN, value).apply() }
    fun downloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
    fun setDownloadId(context: Context, id: Long) { prefs(context).edit().putLong(KEY_DOWNLOAD_ID, id).apply() }
}
