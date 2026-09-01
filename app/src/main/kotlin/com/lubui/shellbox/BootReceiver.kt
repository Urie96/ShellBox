package com.lubui.shellbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

import com.lubui.shellbox.util.ProxyHttpServer
import com.lubui.shellbox.util.PushConfig

/**
 * 开机自动启动 HTTP 服务和推送服务。
 *
 * - 监听 BOOT_COMPLETED（开机）和 MY_PACKAGE_REPLACED（应用更新后），
 *   是否自启由应用内开关决定（SharedPreferences: http_server / auto_start、push / auto_start）。
 * - Android 12+ 从 BOOT_COMPLETED 启动前台服务属于豁免场景，不会抛
 *   ForegroundServiceStartNotAllowedException。
 * - 重启后 Shizuku 的 shell 授权需要重新走 adb 起服务，Sui 的 root 授权是持久的；
 *   服务未授权时 /proxy 请求会返回 403，不影响服务本身启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        startIfEnabled(context, intent, ProxyHttpServer::isAutoStartEnabled, ProxyHttpService::class.java)
        startIfEnabled(context, intent, PushConfig::isAutoStartEnabled, NtfyPushService::class.java)
    }

    private fun startIfEnabled(
        context: Context,
        intent: Intent,
        enabledCheck: (Context) -> Boolean,
        serviceClass: Class<*>
    ) {
        if (!enabledCheck(context)) return
        try {
            val serviceIntent = Intent(context, serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "started ${serviceClass.simpleName} on ${intent.action}")
        } catch (tr: Throwable) {
            Log.e(TAG, "failed to start ${serviceClass.simpleName} on ${intent.action}", tr)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
