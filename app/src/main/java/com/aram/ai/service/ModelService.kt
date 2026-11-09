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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var apiServer: ApiHttpServer? = null
    @Volatile private var starting = false
    @Volatile private var stopRequested = false
    private val lifecycleMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            return START_NOT_STICKY
        }

        try {
            AppPrefs.setEngineState(this, "STARTING", "يجري بدء خدمة المحرك…")
            startForegroundCompat()
            acquireCpuWakeLock()
        } catch (error: Exception) {
            active = false
            AppPrefs.setEngineState(this, "ERROR", "تعذر بدء خدمة Android: ${error.message ?: error.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!starting && apiServer == null) startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        if (starting || apiServer != null) return
        stopRequested = false
        starting = true
        AppPrefs.setEngineState(this, "LOADING_MODEL", "يجري تحميل النموذج في الذاكرة…")
        updateNotification("يجري تحميل Spark-X2.6-1.7B…")
        serviceScope.launch {
            lifecycleMutex.withLock {
                try {
                    if (stopRequested) return@withLock
                    val model = ModelFiles.modelFile(this@ModelService)
                    require(model.isFile && model.length() >= ModelFiles.MIN_MODEL_BYTES) {
                        "ملف النموذج غير موجود أو حجمه غير مكتمل. أعد تنزيله من الصفحة الرئيسية."
                    }

                    ModelRuntime.load(this@ModelService, model)
                    if (stopRequested) {
                        ModelRuntime.unload()
                        starting = false
                        active = false
                        AppPrefs.setEngineState(this@ModelService, "STOPPED", "تم إلغاء التشغيل وتحرير الذاكرة")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        releaseCpuWakeLock()
                        stopSelf()
                        return@withLock
                    }

                    AppPrefs.setEngineState(this@ModelService, "STARTING_SERVER", "تم تحميل النموذج؛ يجري تشغيل خادم API…")
                    updateNotification("تم تحميل النموذج · يجري تشغيل API…")

                    val app = application as AramAiApplication
                    val host = if (AppPrefs.allowLan(this@ModelService)) "0.0.0.0" else "127.0.0.1"
                    val server = ApiHttpServer(host, app.apiKeyStore)
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    apiServer = server

                    starting = false
                    active = true
                    AppPrefs.setEngineState(this@ModelService, "READY", "المحرك جاهز · خادم API يعمل على المنفذ 8765")
                    updateNotification("النموذج جاهز · API على المنفذ 8765")
                } catch (error: Throwable) {
                    starting = false
                    active = false
                    runCatching { apiServer?.stop() }
                    apiServer = null
                    runCatching { ModelRuntime.unload() }
                    val detail = error.message ?: error.javaClass.simpleName
                    AppPrefs.setEngineState(this@ModelService, "ERROR", "فشل تشغيل المحرك: $detail")
                    updateNotification("فشل تشغيل Aram AI · افتح التطبيق للتفاصيل")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    releaseCpuWakeLock()
                    stopSelf()
                }
            }
        }
    }

    private fun stopEngine() {
        stopRequested = true
        active = false
        AppPrefs.setEngineState(this, "STOPPING", "يجري إيقاف المحرك وتحرير الذاكرة…")
        serviceScope.launch {
            lifecycleMutex.withLock {
                runCatching { apiServer?.stop() }
                apiServer = null
                runCatching { ModelRuntime.unload() }
                active = false
                starting = false
                AppPrefs.setEngineState(this@ModelService, "STOPPED", "المحرك متوقف وتم تحرير موارد التشغيل")
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseCpuWakeLock()
                stopSelf()
            }
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
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
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
        active = false
        serviceAlive = false
        starting = false
        runCatching { apiServer?.stop() }
        apiServer = null
        releaseCpuWakeLock()
        val current = AppPrefs.engineStatus(this)
        if (current.state != "ERROR" && current.state != "STOPPED") {
            AppPrefs.setEngineState(this, "STOPPED", "توقفت خدمة المحرك")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aram.ai.action.START"
        const val ACTION_STOP = "com.aram.ai.action.STOP"
        private const val CHANNEL_ID = "aram_ai_engine"
        private const val NOTIFICATION_ID = 4107
        @Volatile var active: Boolean = false
            private set
        @Volatile var serviceAlive: Boolean = false
            private set
    }
}
