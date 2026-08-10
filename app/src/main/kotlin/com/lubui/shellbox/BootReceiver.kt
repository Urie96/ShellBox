package com.lubui.shellbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

import com.lubui.shellbox.util.ProxyHttpServer

/**
 * 开机自动启动 HTTP 服务。
 *
 * - 监听 BOOT_COMPLETED（开机）和 MY_PACKAGE_REPLACED（应用更新后），
 *   是否自启由应用内「开机自启」开关决定（SharedPreferences: http_server / auto_start）。
 * - Android 12+ 从 BOOT_COMPLETED 启动前台服务属于豁免场景，不会抛
 *   ForegroundServiceStartNotAllowedException。
 * - 重启后 Shizuku 的 shell 授权需要重新走 adb 起服务，Sui 的 root 授权是持久的；
 *   服务未授权时 /proxy 请求会返回 403，不影响服务本身启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ProxyHttpServer.isAutoStartEnabled(context)) return
        try {
            val serviceIntent = Intent(context, ProxyHttpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "started ProxyHttpService on ${intent.action}")
        } catch (tr: Throwable) {
            Log.e(TAG, "failed to start ProxyHttpService on ${intent.action}", tr)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
