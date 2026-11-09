package com.aram.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aram.ai.AramAiApplication
import com.aram.ai.MainActivity
import com.aram.ai.R
import com.aram.ai.data.AppPrefs
import com.aram.ai.data.ModelFiles
import com.aram.ai.engine.ModelRuntime
import com.aram.ai.server.ApiHttpServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiServer: ApiHttpServer? = null
    private var starting = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            return START_NOT_STICKY
        }
        if (intent == null && !AppPrefs.isRunning(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        acquireCpuWakeLock()
        if (!starting && apiServer == null) startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        starting = true
        serviceScope.launch {
            try {
                val model = ModelFiles.modelFile(this@ModelService)
                ModelRuntime.load(this@ModelService, model)
                val app = application as AramAiApplication
                val host = if (AppPrefs.allowLan(this@ModelService)) "0.0.0.0" else "127.0.0.1"
                val server = ApiHttpServer(host, app.apiKeyStore)
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                apiServer = server
                AppPrefs.setRunning(this@ModelService, true)
                starting = false
                updateNotification("النموذج يعمل · API على المنفذ 8765")
            } catch (error: Exception) {
                runCatching { ModelRuntime.unload() }
                AppPrefs.setRunning(this@ModelService, false)
                starting = false
                updateNotification("تعذر تشغيل المحرك: ${error.message ?: "خطأ غير معروف"}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseCpuWakeLock()
                stopSelf()
            }
        }
    }

    private fun stopEngine() {
        serviceScope.launch {
            runCatching { apiServer?.stop() }
            apiServer = null
            runCatching { ModelRuntime.unload() }
            AppPrefs.setRunning(this@ModelService, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseCpuWakeLock()
            stopSelf()
        }
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AramAI-engine").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun startForegroundCompat() {
        createNotificationChannel()
        val notification = buildNotification("تهيئة محرك الذكاء الاصطناعي…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "خدمة Aram AI", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ModelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aram_ai)
            .setContentTitle("Aram AI Server")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "إيقاف", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        runCatching { apiServer?.stop() }
        apiServer = null
        AppPrefs.setRunning(this, false)
        releaseCpuWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aram.ai.action.START"
        const val ACTION_STOP = "com.aram.ai.action.STOP"
        private const val CHANNEL_ID = "aram_ai_engine"
        private const val NOTIFICATION_ID = 4107
    }
}
