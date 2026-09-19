package com.lubui.shellbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast

import com.lubui.shellbox.util.ProxyHttpServer

/**
 * 承载 [ProxyHttpServer] 的前台服务。
 *
 * Android 8+ 普通后台服务活不过几分钟，必须前台服务才能「常驻」；
 * Android 14（targetSdk 36）还要求声明 foregroundServiceType=dataSync
 * （manifest 中已声明，见 AndroidManifest.xml）。
 *
 * START_STICKY：进程被系统杀死后会自动重建并重新监听（尽力而为；厂商 ROM 会连前台服务
 * 一起 o-kill，真正兜底的是 [ServiceWatchdog]）。
 */
class ProxyHttpService : Service() {

    companion object {
        private const val TAG = "ProxyHttpService"
        private const val CHANNEL_ID = "proxy_http_server"
        private const val NOTIFICATION_ID = 1

        /** 服务是否正在运行（供 UI 显示状态；进程被杀后重启会重新置 true）。 */
        @Volatile
        var running = false
            private set
    }

    private var server: ProxyHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val token = ProxyHttpServer.getToken(this)
        val port = ProxyHttpServer.getPort(this)
        val ip = ProxyHttpServer.getLocalIpAddress() ?: "<获取IP失败>"

        val notification = buildNotification(ip, token, port)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：specialUse 类型，避免 Android 15+ 对 dataSync 的 6 小时时限杀掉常驻服务
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // API 29-33 用两参版本（类型取 manifest 声明）；API 28- 无类型概念
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (tr: Throwable) {
            // 由保活看门狗在后台拉起时，若本应用没被电池优化豁免，Android 12+ 会拒绝前台服务：
            // 不能让异常把进程崩掉，直接收工，等下次检查（或用户打开应用）再试。见 ServiceWatchdog。
            Log.e(TAG, "startForeground failed, stopping service", tr)
            stopSelf()
            return
        }

        server = ProxyHttpServer.create(this).also { it.start() }
        if (server?.isRunning != true) {
            // 端口被占用等原因启动失败：停止服务
            Log.e(TAG, "failed to start HTTP server, stopping service")
            toast("端口 $port 无法监听（可能被占用），HTTP 服务启动失败")
            stopSelf()
        } else {
            running = true
            Log.i(TAG, "HTTP service running on port $port, token=$token")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：被系统杀掉后重建时 intent 为 null，这里只需确保服务已就绪即可
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理 HTTP 服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String, token: String, port: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DemoActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_proxy_on)
            .setContentTitle("代理 HTTP 服务运行中")
            .setContentText("http://$ip:$port · token: $token")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }
}
