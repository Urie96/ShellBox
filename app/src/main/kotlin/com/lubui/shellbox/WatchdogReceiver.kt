package com.lubui.shellbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 保活看门狗闹钟的接收器（见 [ServiceWatchdog]）。
 *
 * 每次收到闹钟：检查一次（必要时拉起服务）→ **再排下一次**（用的是单次
 * `setAndAllowWhileIdle`，不是 repeating，所以必须自续期）。
 * 即使这次检查失败（后台启动被系统拒绝）也会续期，等下一次或用户打开应用时兜底。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ServiceWatchdog.checkAndRestart(context)
        ServiceWatchdog.schedule(context)
    }
}
