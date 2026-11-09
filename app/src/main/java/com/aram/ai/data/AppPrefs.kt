package com.aram.ai.data

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val FILE = "aram_ai_runtime"
    private const val KEY_RUNNING = "server_running"
    private const val KEY_LAN = "allow_lan"
    private const val KEY_DOWNLOAD_ID = "model_download_id"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)
    fun setRunning(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_RUNNING, value).apply() }
    fun allowLan(context: Context): Boolean = prefs(context).getBoolean(KEY_LAN, false)
    fun setAllowLan(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_LAN, value).apply() }
    fun downloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
    fun setDownloadId(context: Context, id: Long) { prefs(context).edit().putLong(KEY_DOWNLOAD_ID, id).apply() }
}
