package com.lubui.shellbox.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lubui.shellbox.ClipboardGhostActivity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 剪贴板读写（HTTP 服务用）。
 *
 * - 写入（[setText]）：`setPrimaryClip` 任何版本、任何状态（后台/息屏）都允许，直接写。
 * - 读取（[getText]）：
 *   1. 前台快路径——本应用持有窗口焦点（[AppFocusState]）时，同一 UID 的 Service 也能读，
 *      `primaryClip` 为 null 即「剪贴板为空」，直接返回，不启动 Activity（无闪屏）。
 *   2. 否则启动透明 [ClipboardGhostActivity] 抢焦点读取（Android 10+ 读剪贴板必须有窗口焦点）。
 *      后台场景要求已授予「显示在其他应用上层」权限（BAL 豁免），否则系统会静默拦截 Activity 启动。
 *
 * 已知副作用/限制：
 * - Android 12+ 每次读取剪贴板，系统都会弹「ShellBox 已粘贴您剪贴板中的内容」toast（无法隐藏）。
 * - 锁屏/息屏时 Activity 拿不到焦点，读取会超时失败（返回 null）。
 */

/** DemoActivity 的窗口焦点状态：有焦点时 [ClipboardApi.directRead] 的 null 可断定是「空」而非「无权限」。 */
object AppFocusState {
    @Volatile
    var hasWindowFocus: Boolean = false
}

/**
 * ghost activity 与 HTTP 服务线程之间的结果通道。
 *
 * 关键约定（防 race）：
 * - [await] 阻塞在 [CompletableFuture.get] 上时**不能先把 future 从 map 移除**——
 *   ghost 是异步的，几毫秒后才调用 [complete]，若 future 已被移除，结果会被静默丢弃，
 *   等待方只能干等超时（曾因此 bug 导致每次 ghost 读取都失败）。
 * - 正确顺序：await 持有 map 中的引用阻塞；complete 移除并 complete 同一个 future。
 * - [await] 结束（成功或超时）后负责清理 map 条目。
 */
object ClipboardReadBus {

    private val pending = ConcurrentHashMap<String, CompletableFuture<String?>>()

    /** 创建一次读取请求，返回 requestId。 */
    fun newRequest(): String {
        val id = UUID.randomUUID().toString()
        pending[id] = CompletableFuture()
        return id
    }

    /** ghost activity 读到结果后回调；请求已被 [await] 超时清理时忽略。 */
    fun complete(id: String, text: String?) {
        pending.remove(id)?.complete(text)
    }

    /** 服务端线程等待结果；超时/异常返回 null。 */
    fun await(id: String, timeoutMs: Long): String? {
        val future = pending[id] ?: return null
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            pending.remove(id)
            result
        } catch (e: Exception) {
            pending.remove(id)
            future.complete(null) // 若 complete 还在路上，让它提前结束，避免等待方挂起
            null
        }
    }
}

object ClipboardApi {

    private const val TAG = "ClipboardApi"

    /** ghost activity 读取等待上限；须小于 HTTP socket 的 10s 超时。 */
    private const val GHOST_TIMEOUT_MS = 3_500L

    /** 写入剪贴板（任何版本、任何状态均可直接写）。 */
    fun setText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
    }

    /**
     * 读取剪贴板文本。
     *
     * @return 剪贴板文本；空剪贴板返回空串；读取失败（超时/锁屏/后台启动被拦）返回 null。
     */
    fun getText(context: Context): String? {
        directRead(context)?.let { return it }

        val requestId = ClipboardReadBus.newRequest()
        try {
            context.startActivity(ClipboardGhostActivity.buildIntent(context, requestId))
        } catch (e: Throwable) {
            Log.w(TAG, "ghost activity launch failed: ${e.message}")
            ClipboardReadBus.await(requestId, 0) // 清掉挂起的 future
            return null
        }
        return ClipboardReadBus.await(requestId, GHOST_TIMEOUT_MS)
    }

    /**
     * 直接读剪贴板（不启动 Activity）。
     * - 返回文本：成功。
     * - 返回空串：本应用有窗口焦点且剪贴板为空（null 只能是「空」，无歧义）。
     * - 返回 null：无焦点（API 29+，可能只是无权限）——需走 ghost 二次确认。
     */
    private fun directRead(context: Context): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: run {
                // 有焦点时 null = 剪贴板为空；无焦点时 null = 无权限（无法区分，走 ghost）
                return if (AppFocusState.hasWindowFocus) "" else null
            }
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "direct clipboard read failed: ${e.message}")
            null
        }
    }
}
