package com.lubui.shellbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 覆盖安装 / 系统时间·时区·语言变化后，按应用内开关启动 HTTP 服务和推送服务，
 * 并重排保活看门狗闹钟（见 [ServiceWatchdog]）。
 *
 * - 这些 action 都属于 Android 12+「允许从后台启动前台服务」的豁免场景，不会抛
 *   ForegroundServiceStartNotAllowedException。
 * - 闹钟不跨重启，所以必须在这里重新 `setAndAllowWhileIdle`；这也是看门狗链路
 *   「开机 → 服务被厂商省电策略杀掉 → 闹钟把它拉回来」的起点。
 * - 重启后 Shizuku 的 shell 授权需要重新走 adb 起服务，Sui 的 root 授权是持久的；
 *   服务未授权时 /proxy 请求会返回 403，不影响服务本身启动。
 *
 * 注意：真正的「启动 + 容错」逻辑都在 [ServiceWatchdog.checkAndRestart] 里，
 * 本接收器只负责在正确的时机触发（避免和看门狗两套启动代码各写一遍）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = ServiceWatchdog.checkAndRestart(context)
        Log.i(TAG, "on ${intent.action}: $result")
        ServiceWatchdog.schedule(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
