package com.lubui.shellbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

import com.lubui.shellbox.util.ProxyHttpServer
import com.lubui.shellbox.util.PushConfig

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保活看门狗：定期检查「自启开关为开但服务没在跑」的情况，并把服务重新拉起来。
 *
 * 为什么需要它：[BootReceiver] 只在**开机**（BOOT_COMPLETED）和**覆盖安装**
 * （MY_PACKAGE_REPLACED）时启动服务；服务的 START_STICKY 只是「系统尽力重建」。
 * 厂商 ROM（ColorOS/MIUI 等）会把整个进程 o-kill 掉（实测 ColorOS `o-kill(37)`，
 * 前台服务也照杀），之后没有任何机制再拉起服务——表现就是「开关明明勾着，
 * 进应用却看到两个服务都是未运行」。
 *
 * 触发点（全部平台 API，零第三方依赖）：
 * 1. [WatchdogReceiver]：`AlarmManager.setAndAllowWhileIdle` **单次**闹钟（非精确、不需
 *    SCHEDULE_EXACT_ALARM、可穿透 Doze），每次检查完再排下一次，间隔 [INTERVAL_MS]；
 * 2. [BootReceiver]：开机 / 覆盖安装 / 系统时间·时区·语言变化后重排闹钟并检查一次
 *    （闹钟不跨重启，必须重建）；
 * 3. [DemoActivity]：每次 onResume 检查一次并补排闹钟（闹钟可能被厂商省电策略清掉）。
 *
 * 注意（AGENTS.md 已知坑 19）：Android 12+ 从后台启动前台服务受限，只有少数豁免场景
 * 允许（「用户关闭了本应用的电池优化」是最实用的一条；SYSTEM_ALERT_WINDOW 在
 * targetSdk 35+ 还要求当前有可见悬浮窗，不能依赖）。所以 [checkAndRestart] 必须
 * 容错：启动失败就把原因记下来给 UI 显示，等下次检查或下次打开应用（前台必定允许）兜底。
 */
object ServiceWatchdog {

    private const val TAG = "ServiceWatchdog"

    /** 检查间隔：15 分钟（Doze 下 `setAndAllowWhileIdle` 可能被推迟，但不会丢）。 */
    const val INTERVAL_MS = 15 * 60 * 1000L

    private const val REQUEST_CODE = 0x5B07
    private const val ACTION_CHECK = "com.lubui.shellbox.action.WATCHDOG_CHECK"

    private const val PREFS = "watchdog"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_RESULT = "last_result"

    /** 至少有一个自启开关是开的（都关着就不用排闹钟）。 */
    fun isEnabled(context: Context): Boolean =
        ProxyHttpServer.isAutoStartEnabled(context) || PushConfig.isAutoStartEnabled(context)

    /**
     * 排下一次检查。重复调用只会替换上一次（同一个 PendingIntent），不会堆积；
     * 两个开关都关着时等于 [cancel]。
     */
    fun schedule(context: Context) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pi
            )
        } catch (tr: Throwable) {
            Log.e(TAG, "schedule watchdog failed", tr)
        }
    }

    /** 取消看门狗闹钟（两个自启开关都关掉时调用）。 */
    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    /** 上次检查的时间戳（毫秒），0 = 从未检查过。 */
    fun lastCheckTime(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** 上次检查的结果（人话），null = 从未检查过。 */
    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)

    /** 给 UI 用的时间格式化。 */
    fun formatTime(timestamp: Long): String =
        if (timestamp <= 0L) "从未" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    /**
     * 检查两个服务是否该在跑却没跑，需要就重新启动；并把结果持久化（供 UI 显示）。
     *
     * 可在任意上下文调用：Activity 前台（必定被允许启动前台服务）或闹钟广播（后台，
     * 需电池优化豁免）。启动异常一律在 [restart] 里吞掉并记录，不会崩进程。
     *
     * @return true 表示这次检查真的尝试启动过服务（调用方通常需要稍后刷新 UI 状态）
     */
    fun checkAndRestart(context: Context): Boolean {
        val parts = mutableListOf<String>()
        var attempted = false

        if (ProxyHttpServer.isAutoStartEnabled(context) && !ProxyHttpService.running) {
            parts += restart(context, ProxyHttpService::class.java)
            attempted = true
        }
        if (PushConfig.isAutoStartEnabled(context)) {
            when {
                !PushConfig.isConfigured(context) -> parts += "推送未配置，跳过"
                !NtfyPushService.running -> {
                    parts += restart(context, NtfyPushService::class.java)
                    attempted = true
                }
            }
        }

        val result = if (parts.isEmpty()) "服务都在运行" else parts.joinToString("；")
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, result)
            .apply()
        Log.i(TAG, "check: $result")
        return attempted
    }

    private fun restart(context: Context, serviceClass: Class<*>): String {
        return try {
            val intent = Intent(context, serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            "已启动 ${serviceClass.simpleName}"
        } catch (tr: Throwable) {
            // Android 12+ 后台启动前台服务被系统拒绝（ForegroundServiceStartNotAllowedException）
            // 会走到这里——常见原因是没把本应用加入电池优化白名单/厂商后台白名单。
            // 不抛给调用方（闹钟广播里抛出去等于崩进程），记下来给 UI 显示。
            Log.e(TAG, "failed to start ${serviceClass.simpleName}", tr)
            "启动 ${serviceClass.simpleName} 被系统拒绝(${tr.javaClass.simpleName})"
        }
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_CHECK),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
