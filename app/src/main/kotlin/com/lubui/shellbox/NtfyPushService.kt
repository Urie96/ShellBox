package com.lubui.shellbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast

import com.lubui.shellbox.util.NtfyClient
import com.lubui.shellbox.util.PushApi
import com.lubui.shellbox.util.PushConfig

/**
 * 承载 ntfy 订阅循环的前台服务（服务端推送）。
 *
 * 与 [ProxyHttpService] 同样的常驻策略：specialUse 前台服务 + START_STICKY。
 * 手机**主动向外**长轮询连接 ntfy 服务器，所以手机在任何网络（家里 WiFi/流量/在外面）都能收到推送；
 * NAS 侧只需 `curl -d "消息" https://你的ntfy服务器/topic`，不需要知道手机在哪。
 *
 * - 断线自动指数退避重连（见 [NtfyClient]）；重连用 `since=<lastId>` 补收离线期间错过的消息
 *   （ntfy 服务器默认缓存 12h）。
 * - 首次运行用 `since=<当前unix秒>`：只收「此刻之后」的消息，避免把服务器缓存的旧消息全弹出来。
 * - 网络切换（WiFi↔流量）由 [ConnectivityManager.NetworkCallback] 触发立即重连。
 */
class NtfyPushService : Service() {

    companion object {
        private const val TAG = "NtfyPushService"
        private const val CHANNEL_ID = "push_service"
        private const val NOTIFICATION_ID = 2

        /** 服务是否正在运行（供 UI 显示；进程被杀后 START_STICKY 重建会重新置 true）。 */
        @Volatile
        var running = false
            private set

        /** 最近一次订阅错误（供 UI 显示）；null = 目前正常。 */
        @Volatile
        var lastError: String? = null
            private set

        /** 本次运行已收到的推送条数（供 UI 显示，验证订阅链路）。 */
        @Volatile
        var receivedCount = 0
            private set

        /** 最后一条收到的消息预览（供 UI 显示）。 */
        @Volatile
        var lastMessagePreview: String? = null
            private set
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 网络回调首次触发（注册后立即回调一次）不应打断刚发起的连接，后续变更才触发重连。 */
    private var firstNetworkCallback = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 先 startForeground 再检查配置：本服务由 startForegroundService() 启动，
        // 必须先在前台声明，配置缺失时再 stopSelf（与 ProxyHttpService 相同的顺序约定）
        val server = PushConfig.getServer(this)
        val topic = PushConfig.getTopic(this)
        val notification = buildNotification(
            server.ifBlank { "<未配置>" },
            topic.ifBlank { "<未配置>" }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+：specialUse 类型，避免 Android 15+ 对 dataSync 的 6 小时时限杀掉常驻服务
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // API 29-33 用两参版本；API 28- 无类型概念
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        if (server.isBlank() || topic.isBlank()) {
            Log.e(TAG, "push not configured, stopping service")
            toast("推送未配置（服务器/topic 为空），请在应用内先设置")
            stopSelf()
            return
        }

        startSubscribe()
        registerNetworkCallback()
        running = true
        Log.i(TAG, "push service running: $server/$topic")
    }

    private fun startSubscribe() {
        lastError = null
        val server = PushConfig.getServer(this)
        val topic = PushConfig.getTopic(this)
        val token = PushConfig.getToken(this)
        // 首次运行：since=当前 unix 秒（只收此刻之后）；之后重连/重启：since=<lastId> 补消息
        val initialSince = PushConfig.getLastId(this) ?: (System.currentTimeMillis() / 1000).toString()
        NtfyClient.startSubscribe(
            server = server,
            topic = topic,
            token = token,
            initialSince = initialSince,
            onMessage = { msg ->
                receivedCount++
                lastMessagePreview = ((msg.title ?: "") + " " + msg.body).trim().take(60)
                PushApi.notifyMessage(this, msg)
                PushConfig.setLastId(this, msg.id)
                Log.i(TAG, "push received: ${msg.title ?: ""} ${msg.body.take(50)}")
            },
            onError = { err ->
                lastError = err
            },
            onConnected = {
                lastError = null
            }
        )
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (firstNetworkCallback) {
                    // 注册时立即回调一次是正常现象，跳过；真正的网络切换才重连
                    firstNetworkCallback = false
                    return
                }
                Log.i(TAG, "network available, reconnect now")
                NtfyClient.reconnectNow()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(networkCallback!!) }
            .onFailure { Log.e(TAG, "register network callback failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        NtfyClient.stopSubscribe()
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "推送服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(server: String, topic: String): Notification {
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
            .setSmallIcon(R.drawable.ic_push)
            .setContentTitle("推送服务运行中")
            .setContentText("$server/$topic")
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
