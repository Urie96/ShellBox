package com.lubui.shellbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

import rikka.shizuku.Shizuku
import com.lubui.shellbox.databinding.MainActivityBinding
import com.lubui.shellbox.util.AppFocusState
import com.lubui.shellbox.util.NtfyClient
import com.lubui.shellbox.util.ProxyHistory
import com.lubui.shellbox.util.ProxyHttpServer
import com.lubui.shellbox.util.PushConfig
import com.lubui.shellbox.util.SettingsGlobalUtils

@SuppressLint("SetTextI18n")
class DemoActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_SET_PROXY = 5
        private const val REQUEST_CODE_CLEAR_PROXY = 6
        private const val REQUEST_CODE_HTTP_SERVER = 7
        private const val REQUEST_CODE_NOTIFICATION = 8
    }

    private lateinit var binding: MainActivityBinding

    /** 等待通知权限授权的服务 Intent（授权回调后真正启动）。 */
    private var pendingServiceIntent: Intent? = null

    private val BINDER_RECEIVED_LISTENER = Shizuku.OnBinderReceivedListener {
        if (Shizuku.isPreV11()) {
            binding.text1.text = "Shizuku pre-v11 is not supported"
        } else {
            binding.text1.text = "Binder received"
        }
    }
    private val BINDER_DEAD_LISTENER = Shizuku.OnBinderDeadListener { binding.text1.text = "Binder dead" }
    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener(::onRequestPermissionsResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.text2.text = "Using " + (if (DemoApplication.isSui()) "Sui" else "Shizuku or nothing is installed") + "."

        binding.text1.text = "Waiting for binder"
        binding.buttonSetProxy.setOnClickListener { if (checkPermission(REQUEST_CODE_SET_PROXY)) setProxy() }
        binding.buttonClearProxy.setOnClickListener { if (checkPermission(REQUEST_CODE_CLEAR_PROXY)) clearProxy() }
        binding.buttonHttpToggle.setOnClickListener { toggleHttpServer() }
        binding.checkAutoStart.isChecked = ProxyHttpServer.isAutoStartEnabled(this)
        binding.checkAutoStart.setOnCheckedChangeListener { _, checked ->
            ProxyHttpServer.setAutoStartEnabled(this, checked)
            if (checked) {
                // 勾选即生效：立刻启动一次（应用可见时启动前台服务必定被允许），并排上保活闹钟
                if (!ProxyHttpService.running) startServiceInternal(Intent(this, ProxyHttpService::class.java))
                ServiceWatchdog.schedule(this)
                binding.text3.text = keepAliveHint()
            } else {
                ServiceWatchdog.schedule(this) // 另一个开关可能还开着；都关了 schedule 内部会取消
            }
            updateWatchdogStatus()
        }
        binding.buttonOverlayPermission.setOnClickListener { requestOverlayPermission() }
        updateOverlayStatus()

        binding.buttonPushToggle.setOnClickListener { togglePush() }
        binding.buttonPushConfig.setOnClickListener { showPushConfigDialog() }
        binding.buttonPushTest.setOnClickListener { testPush() }
        binding.checkPushAutoStart.isChecked = PushConfig.isAutoStartEnabled(this)
        binding.checkPushAutoStart.setOnCheckedChangeListener { _, checked ->
            PushConfig.setAutoStartEnabled(this, checked)
            if (checked) {
                // 勾选即生效：立刻启动一次（没配置则引导去配置）
                if (!PushConfig.isConfigured(this)) {
                    binding.text3.text = "请先填写推送设置（服务器 / topic），填完自动保活才会生效"
                    showPushConfigDialog()
                } else {
                    if (!NtfyPushService.running) {
                        startServiceWithNotificationPermission(Intent(this, NtfyPushService::class.java))
                    }
                    binding.text3.text = keepAliveHint()
                }
                ServiceWatchdog.schedule(this)
            } else {
                ServiceWatchdog.schedule(this)
            }
            updateWatchdogStatus()
        }
        updatePushStatus()

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER)
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    override fun onResume() {
        super.onResume()
        // 打开应用时兜底：自动保活开关为开但服务没在跑（例如被厂商省电策略杀掉进程）就重新拉起；
        // 同时补排保活闹钟（闹钟可能被厂商省电策略清掉）。应用可见时启动前台服务必定被允许。
        val acted = ServiceWatchdog.checkAndRestart(this)
        ServiceWatchdog.schedule(this)
        updateHttpStatus()
        updatePushStatus() // 从设置页/系统设置返回后刷新
        updateOverlayStatus() // 从系统悬浮窗设置页返回后刷新
        updateWatchdogStatus()
        if (acted) {
            // service onCreate 是异步的，稍等再刷新一遍
            binding.root.postDelayed({
                updateHttpStatus()
                updatePushStatus()
                updateWatchdogStatus()
            }, 600)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 供 ClipboardApi 判断「前台 + 剪贴板为空」时无需启动 ghost activity
        AppFocusState.hasWindowFocus = hasFocus
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER)
        Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                REQUEST_CODE_SET_PROXY -> setProxy()
                REQUEST_CODE_CLEAR_PROXY -> clearProxy()
                REQUEST_CODE_HTTP_SERVER -> startServiceWithNotificationPermission(Intent(this, ProxyHttpService::class.java))
            }
        } else {
            binding.text1.text = "User denied permission"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            // 通知权限被拒不影响服务本身，照常启动（HTTP 服务或推送服务，见 pendingServiceIntent）
            pendingServiceIntent?.let { intent ->
                pendingServiceIntent = null
                startServiceInternal(intent)
            }
        }
    }

    private fun checkPermission(code: Int): Boolean {
        if (Shizuku.isPreV11()) {
            return false
        }
        return try {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                binding.text3.text = "User denied permission (shouldShowRequestPermissionRationale=true)"
                false
            } else {
                Shizuku.requestPermission(code)
                false
            }
        } catch (tr: Throwable) {
            binding.text3.text = Log.getStackTraceString(tr)
            false
        }
    }

    private fun setProxy() {
        val view = layoutInflater.inflate(R.layout.dialog_proxy, null)
        val editText = view.findViewById<EditText>(R.id.edit_proxy_dialog)
        val listView = view.findViewById<ListView>(R.id.list_recent_proxies)

        val history = ProxyHistory.getHistory(this)
        if (history.isEmpty()) {
            listView.visibility = View.GONE
        } else {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
            // 点击历史地址：填入输入框，可再编辑后确认
            listView.setOnItemClickListener { _, _, position, _ ->
                editText.setText(history[position])
                editText.setSelection(editText.text.length)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("开启代理")
            .setView(view)
            .setPositiveButton("开启") { _, _ -> applyProxy(editText.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyProxy(proxy: String) {
        val res = StringBuilder()
        try {
            val trimmed = proxy.trim()
            if (trimmed.isEmpty()) {
                res.append("proxy is empty, use \"关闭代理\" to disable")
            } else if (trimmed.indexOf(':') == -1) {
                res.append("invalid proxy, expected format: host:port")
            } else {
                SettingsGlobalUtils.putGlobal("http_proxy", trimmed)
                ProxyHistory.add(this, trimmed)
                res.append("set http_proxy=").append(trimmed).append('\n')
                res.append("now http_proxy=").append(SettingsGlobalUtils.getGlobal("http_proxy"))
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(Log.getStackTraceString(tr))
        }
        binding.text3.text = res.toString().trim()
    }

    private fun clearProxy() {
        val res = StringBuilder()
        try {
            SettingsGlobalUtils.putGlobal("http_proxy", ":0")
            res.append("cleared http_proxy (set to :0)\n")
            res.append("now http_proxy=").append(SettingsGlobalUtils.getGlobal("http_proxy"))
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(Log.getStackTraceString(tr))
        }
        binding.text3.text = res.toString().trim()
    }

    // ---- 后台剪贴板读取：需「显示在其他应用上层」权限（BAL 豁免） ----

    /** 悬浮窗权限状态按钮文案：后台读剪贴板的前置条件（Android 10+ 后台启动 Activity 被系统限制）。 */
    private fun updateOverlayStatus() {
        val granted = Settings.canDrawOverlays(this)
        binding.buttonOverlayPermission.text = if (granted) {
            "后台读取剪贴板：已授权悬浮窗"
        } else {
            "后台读取剪贴板：未授权（点此开启）"
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            binding.text3.text = "已授予「显示在其他应用上层」权限，后台可直接读剪贴板"
            updateOverlayStatus()
            return
        }
        // 引导到系统设置页；返回应用后 onResume 刷新按钮状态
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }.onFailure { tr ->
            binding.text3.text = Log.getStackTraceString(tr)
        }
    }

    // ---- HTTP 服务开关 ----

    private fun toggleHttpServer() {
        if (ProxyHttpService.running) {
            stopService(Intent(this, ProxyHttpService::class.java))
            // 手动停服务 = 明确表达「现在不要它跑」：同时关掉自动保活，
            // 否则 15 分钟后看门狗（或下次打开应用）会把它拉回来
            if (ProxyHttpServer.isAutoStartEnabled(this)) {
                binding.checkAutoStart.isChecked = false // 触发 listener：写 prefs + 重排/取消闹钟
                binding.text3.text = "已停止 HTTP 服务，并关闭「自动保活」（否则会被自动重启）"
            }
            // onDestroy 是异步的，稍等再刷新按钮状态
            binding.buttonHttpToggle.postDelayed({ updateHttpStatus() }, 300)
            return
        }
        showPortDialog()
    }

    /** 启动前弹窗让用户自定义端口（预填当前配置），确认后走授权流程启动服务。 */
    private fun showPortDialog() {
        val density = resources.displayMetrics.density
        val editText = EditText(this).apply {
            setText(ProxyHttpServer.getPort(this@DemoActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("HTTP 服务端口")
            .setMessage("自定义监听端口（1-65535）")
            .setView(editText)
            .setPositiveButton("确定", null) // 点击行为在 setOnShowListener 里接管，便于校验失败时不关闭
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            editText.selectAll() // 方便直接覆盖输入
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val port = editText.text.toString().trim().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    editText.error = "请输入 1-65535 的端口号"
                    return@setOnClickListener
                }
                ProxyHttpServer.setPort(this, port)
                dialog.dismiss()
                if (checkPermission(REQUEST_CODE_HTTP_SERVER)) {
                    startServiceWithNotificationPermission(Intent(this, ProxyHttpService::class.java))
                }
            }
        }
        dialog.show()
    }

    private fun startServiceWithNotificationPermission(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingServiceIntent = intent
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATION)
        } else {
            startServiceInternal(intent)
        }
    }

    private fun startServiceInternal(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // onCreate 是异步的，稍等再刷新按钮状态
            binding.buttonHttpToggle.postDelayed({ updateHttpStatus() }, 500)
            binding.buttonPushToggle.postDelayed({ updatePushStatus() }, 500)
        } catch (tr: Throwable) {
            binding.text3.text = Log.getStackTraceString(tr)
        }
    }

    private fun startHttpServer() = startServiceInternal(Intent(this, ProxyHttpService::class.java))

    private fun updateHttpStatus() {
        val running = ProxyHttpService.running
        val port = ProxyHttpServer.getPort(this)
        binding.buttonHttpToggle.text = if (running) "停止 HTTP 服务" else "启动 HTTP 服务"
        binding.textHttpStatus.text = if (running) {
            val ip = ProxyHttpServer.getLocalIpAddress() ?: "<获取IP失败>"
            "运行中: http://$ip:$port\n" +
                "token: ${ProxyHttpServer.getToken(this)}\n" +
                "USB 模式: adb reverse tcp:$port tcp:$port"
        } else {
            "HTTP 服务未运行\n" +
                "token: ${ProxyHttpServer.getToken(this)}\n" +
                "当前端口: $port（点「启动 HTTP 服务」可修改）\n" +
                "启动后同一 WiFi 访问 http://<手机IP>:$port，或 USB 连接后执行:\n" +
                "adb reverse tcp:$port tcp:$port"
        }
    }

    // ---- 服务端推送（ntfy）：手机长轮询订阅自建 ntfy 服务器，NAS 上 curl 即可发通知 ----

    private fun togglePush() {
        if (NtfyPushService.running) {
            stopService(Intent(this, NtfyPushService::class.java))
            // 同上：手动停服务同时关掉自动保活，避免被看门狗拉回来
            if (PushConfig.isAutoStartEnabled(this)) {
                binding.checkPushAutoStart.isChecked = false
                binding.text3.text = "已停止推送服务，并关闭「自动保活」（否则会被自动重启）"
            }
            // onDestroy 是异步的，稍等再刷新按钮状态
            binding.buttonPushToggle.postDelayed({ updatePushStatus() }, 300)
            return
        }
        if (!PushConfig.isConfigured(this)) {
            binding.text3.text = "请先填写推送设置（服务器 / topic）"
            showPushConfigDialog()
            return
        }
        startServiceWithNotificationPermission(Intent(this, NtfyPushService::class.java))
    }

    /** 推送设置弹窗：服务器地址 / topic / token（token 可选但强烈建议）。 */
    private fun showPushConfigDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        fun editText(initial: String, hint: String, inputType: Int) = EditText(this).apply {
            setText(initial)
            this.hint = hint
            this.inputType = inputType
            setPadding(pad, pad, pad, pad)
        }
        val etServer = editText(
            PushConfig.getServer(this),
            "https://push.你的域名（ntfy 服务器地址）",
            InputType.TYPE_TEXT_VARIATION_URI
        )
        val etTopic = editText(
            PushConfig.getTopic(this),
            "shellbox_随机topic（相当于密码）",
            InputType.TYPE_CLASS_TEXT
        )
        val etToken = editText(
            PushConfig.getToken(this),
            "tk_xxx（访问令牌，可选但强烈建议）",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(etServer)
            addView(etTopic)
            addView(etToken)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("推送设置（ntfy）")
            .setMessage("NAS 上发推送：\ncurl -d \"消息\" <服务器>/<topic>")
            .setView(container)
            .setPositiveButton("保存", null) // 点击行为在 setOnShowListener 里接管，便于校验失败时不关闭
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newServer = etServer.text.toString().trim()
                val newTopic = etTopic.text.toString().trim()
                val newToken = etToken.text.toString().trim()
                if (newServer.isBlank() || newTopic.isBlank()) {
                    binding.text3.text = "服务器和 topic 不能为空"
                    return@setOnClickListener
                }
                val changed = newServer != PushConfig.getServer(this) || newTopic != PushConfig.getTopic(this)
                PushConfig.setServer(this, newServer)
                PushConfig.setTopic(this, newTopic)
                PushConfig.setToken(this, newToken)
                if (changed) PushConfig.clearLastId(this) // 换 topic 后丢弃旧 id，避免把旧消息 id 传给新 topic
                dialog.dismiss()
                updatePushStatus()
                // 服务正在运行时改配置：重启生效（订阅循环持有旧的 server/topic）
                if (NtfyPushService.running) {
                    stopService(Intent(this, NtfyPushService::class.java))
                    binding.buttonPushToggle.postDelayed({
                        startServiceWithNotificationPermission(Intent(this, NtfyPushService::class.java))
                    }, 400)
                }
            }
        }
        dialog.show()
    }

    /** 测试推送：手机直接发布一条消息到 topic，走完整链路（发布→服务器→订阅循环→通知）。 */
    private fun testPush() {
        if (!PushConfig.isConfigured(this)) {
            binding.text3.text = "请先配置推送（服务器 / topic）"
            showPushConfigDialog()
            return
        }
        if (!NtfyPushService.running) {
            binding.text3.text = "请先启动推送服务，再发送测试推送"
            return
        }
        binding.textPushStatus.text = "正在发送测试推送…"
        Thread {
            try {
                NtfyClient.publish(
                    PushConfig.getServer(this),
                    PushConfig.getTopic(this),
                    PushConfig.getToken(this),
                    "ShellBox 测试推送",
                    "如果你看到这条通知，推送链路已打通 🎉",
                    4
                )
                binding.root.post {
                    binding.textPushStatus.text = "测试推送已发出，通知应 1-2 秒内弹出（重要通道，横幅+声音）"
                }
            } catch (tr: Throwable) {
                tr.printStackTrace()
                binding.root.post {
                    binding.textPushStatus.text = "测试推送发送失败"
                    binding.text3.text = "测试推送失败: ${tr.message}"
                }
            }
        }.start()
    }

    /** 勾选「自动保活」时的提示文案。 */
    private fun keepAliveHint(): String =
        "已开启自动保活：开机自启 + 被杀后自动重启（每 ${ServiceWatchdog.INTERVAL_MS / 60000} 分钟检查一次）"

    /** 保活看门狗状态：开关是否启用 + 上次检查时间和结果（诊断「为什么服务又没了」）。 */
    private fun updateWatchdogStatus() {
        binding.textWatchdogStatus.text = if (!ServiceWatchdog.isEnabled(this)) {
            "保活看门狗：未启用（两个自动保活开关都未勾选）"
        } else {
            val last = ServiceWatchdog.formatTime(ServiceWatchdog.lastCheckTime(this))
            val result = ServiceWatchdog.lastResult(this) ?: "-"
            "保活看门狗：每 ${ServiceWatchdog.INTERVAL_MS / 60000} 分钟检查一次\n" +
                "上次检查: $last · $result"
        }
    }

    private fun updatePushStatus() {
        val running = NtfyPushService.running
        binding.buttonPushToggle.text = if (running) "停止推送服务" else "启动推送服务"
        val configured = PushConfig.isConfigured(this)
        val server = PushConfig.getServer(this)
        val topic = PushConfig.getTopic(this)
        val err = NtfyPushService.lastError
        binding.textPushStatus.text = if (!configured) {
            "推送服务未配置\n点「推送设置」填写 ntfy 服务器 / topic / token"
        } else {
            buildString {
                append(if (running) "推送服务运行中" else "推送服务未运行").append('\n')
                append("服务器: ").append(server).append('\n')
                append("topic: ").append(topic).append('\n')
                if (PushConfig.getToken(this@DemoActivity).isNotBlank()) {
                    append("token: ").append(PushConfig.getToken(this@DemoActivity)).append('\n')
                }
                append("NAS 用法: curl -d \"消息\" ").append(server).append('/').append(topic).append('\n')
                append("已收到通知: ").append(NtfyPushService.receivedCount).append(" 条").append('\n')
                NtfyPushService.lastMessagePreview?.let { append("最后一条: ").append(it).append('\n') }
                err?.let { append("最近错误: ").append(it) }
            }.trimEnd()
        }
    }
}
