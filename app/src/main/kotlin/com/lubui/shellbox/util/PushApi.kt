package com.lubui.shellbox.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

import com.lubui.shellbox.DemoActivity
import com.lubui.shellbox.R

import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 ntfy 消息渲染成系统通知。
 *
 * - ntfy priority（1-5）→ Android 通知通道（用户可在系统设置里单独调每个通道的响铃/横幅）：
 *   1-2 → 静默通道（`push_low`）；3 → 默认通道（`push_default`）；4-5 → 重要通道（`push_high`，横幅+声音）。
 * - 每次推送都是新通知（ID 递增），互不覆盖。
 * - 点击行为：消息带 click 字段且为 http/https → 打开浏览器；否则打开应用主界面。
 * - tags 常见 emoji 短码 → 前缀到标题（不认识的不处理）。
 */
object PushApi {

    private const val TAG = "PushApi"

    private const val CHANNEL_LOW = "push_low"
    private const val CHANNEL_DEFAULT = "push_default"
    private const val CHANNEL_HIGH = "push_high"

    private const val MAX_TITLE = 100
    private const val MAX_BODY = 2000

    private val idCounter = AtomicInteger(10000)

    @Volatile
    private var channelsCreated = false

    /** 发送一条推送通知；返回是否真正展示（false = 通知被系统级关闭，如 POST_NOTIFICATIONS 被拒）。 */
    fun notifyMessage(context: Context, msg: NtfyClient.Message): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "notifications disabled, drop push: ${msg.title ?: msg.body.take(50)}")
            return false
        }
        ensureChannels(context)

        val channel = when {
            msg.priority >= 4 -> CHANNEL_HIGH
            msg.priority <= 2 -> CHANNEL_LOW
            else -> CHANNEL_DEFAULT
        }
        val title = emojiPrefix(msg) + (msg.title ?: "ShellBox 推送")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_push)
            .setContentTitle(title.take(MAX_TITLE))
            .setContentText(msg.body.take(MAX_BODY))
            .setAutoCancel(true)
            .setContentIntent(clickIntent(context, msg.click))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // O+ 的响铃/横幅由通道决定；pre-O 手动映射
            @Suppress("DEPRECATION")
            builder.setPriority(
                when {
                    msg.priority >= 4 -> Notification.PRIORITY_HIGH
                    msg.priority <= 2 -> Notification.PRIORITY_LOW
                    else -> Notification.PRIORITY_DEFAULT
                }
            )
        }
        nm.notify(idCounter.getAndIncrement(), builder.build())
        return true
    }

    private fun clickIntent(context: Context, click: String?): PendingIntent {
        // 只放行 http/https，防止被用来触发 geo:/tel:/intent: 等任意 URI
        val url = click?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val intent = if (url != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            Intent(context, DemoActivity::class.java)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** tags → emoji 前缀（只识别映射表里的常见短码，其余忽略）。 */
    private fun emojiPrefix(msg: NtfyClient.Message): String {
        val sb = StringBuilder()
        for (tag in msg.tags) TAG_EMOJI[tag]?.let(sb::append)
        return sb.toString()
    }

    private fun ensureChannels(context: Context) {
        if (channelsCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LOW, "推送（静默）", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_DEFAULT, "推送（默认）", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_HIGH, "推送（重要）", NotificationManager.IMPORTANCE_HIGH)
            )
        )
        channelsCreated = true
    }

    /** ntfy tags（emoji 短码）→ Android 通知标题前缀。 */
    private val TAG_EMOJI = mapOf(
        "rocket" to "🚀",
        "white_check_mark" to "✅",
        "heavy_check_mark" to "✔️",
        "x" to "❌",
        "warning" to "⚠️",
        "alarm" to "🚨",
        "rotating_light" to "🚨",
        "bell" to "🔔",
        "computer" to "💻",
        "package" to "📦",
        "cd" to "💿",
        "email" to "📧",
        "key" to "🔑",
        "lock" to "🔒",
        "no_entry" to "⛔",
        "no_entry_sign" to "🚫",
        "pushpin" to "📌",
        "construction" to "🚧",
        "information_source" to "ℹ️",
        "exclamation" to "❗",
        "question" to "❓",
        "sos" to "🆘",
        "zzz" to "💤",
        "skull" to "💀",
        "tada" to "🎉",
        "loudspeaker" to "📢",
        "+1" to "👍"
    )
}
