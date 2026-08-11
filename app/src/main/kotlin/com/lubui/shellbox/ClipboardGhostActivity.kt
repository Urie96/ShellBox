package com.lubui.shellbox

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lubui.shellbox.util.ClipboardReadBus

/**
 * 透明幽灵 Activity：抢占窗口焦点读取剪贴板后立即关闭。
 *
 * Android 10+ 读剪贴板要求持有窗口焦点，前台服务没有；此 Activity 以全透明主题启动
 * （不可见、无动画、不进最近任务、独立 taskAffinity），拿到焦点后读一次剪贴板，
 * 结果经 [ClipboardReadBus] 交回 HTTP 服务线程，随即 finish。
 *
 * 焦点检测用轮询 [window.hasWindowFocus]，不依赖 onWindowFocusChanged 回调
 * （部分机型/主题下该回调可能不触发）。焦点稳定一段时间后才读，避免把
 * 「刚拿焦点但剪贴板访问尚未放行」的过渡态误判为「剪贴板为空」。
 *
 * 兜底：2.5s 安全超时，无论是否读到都 finish，防止任何情况下卡住前台。
 */
class ClipboardGhostActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardGhost"
        private const val EXTRA_REQUEST_ID = "request_id"

        /** 安全超时：无论如何都会 finish（超过它 = HTTP 侧已超时，结果被丢弃）。 */
        private const val SAFETY_TIMEOUT_MS = 2_500L

        /** 焦点轮询间隔。 */
        private const val POLL_INTERVAL_MS = 100L

        /** 焦点建立后需稳定这么久才读（过渡态防误判）。 */
        private const val FOCUS_SETTLE_MS = 200L

        /** 读到异常后的重试间隔/次数。 */
        private const val RETRY_DELAY_MS = 300L
        private const val MAX_READ_ATTEMPTS = 3

        fun buildIntent(context: Context, requestId: String): Intent =
            Intent(context, ClipboardGhostActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var requestId: String? = null
    private var finished = false
    private var focusSinceMs = 0L
    private var readAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)

        handler.postDelayed({
            if (!finished) {
                Log.w(TAG, "safety timeout — finishing without result")
                finishGhost(null)
            }
        }, SAFETY_TIMEOUT_MS)

        // 立即开始轮询焦点（不依赖 onWindowFocusChanged 回调）
        handler.post { pollRead() }
    }

    private fun pollRead() {
        if (finished) return
        if (!hasWindowFocus()) {
            focusSinceMs = 0L
            handler.postDelayed({ pollRead() }, POLL_INTERVAL_MS)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (focusSinceMs == 0L) focusSinceMs = now
        if (now - focusSinceMs < FOCUS_SETTLE_MS) {
            handler.postDelayed({ pollRead() }, POLL_INTERVAL_MS)
            return
        }

        readAttempts += 1
        val text = readClipboard()
        if (text != null || readAttempts >= MAX_READ_ATTEMPTS) {
            finishGhost(text)
        } else {
            handler.postDelayed({ pollRead() }, RETRY_DELAY_MS)
        }
    }

    /**
     * 读剪贴板。此时窗口已有稳定焦点，`primaryClip` 为 null 只可能是「剪贴板为空」→ 返回空串；
     * 读失败（异常）返回 null 让外层重试。
     */
    private fun readClipboard(): String? {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard read failed: ${e.message}")
            null
        }
    }

    private fun finishGhost(text: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        requestId?.let { ClipboardReadBus.complete(it, text) }

        finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 异常销毁（系统回收等）也要通知等待方，否则 HTTP 侧只能干等超时
        if (!finished) {
            finished = true
            requestId?.let { ClipboardReadBus.complete(it, null) }
        }
        super.onDestroy()
    }
}
