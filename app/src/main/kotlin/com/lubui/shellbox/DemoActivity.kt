package com.lubui.shellbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView

import rikka.shizuku.Shizuku
import com.lubui.shellbox.databinding.MainActivityBinding
import com.lubui.shellbox.util.ProxyHistory
import com.lubui.shellbox.util.ProxyHttpServer
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
        }

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER)
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    override fun onResume() {
        super.onResume()
        updateHttpStatus()
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
                REQUEST_CODE_HTTP_SERVER -> startHttpServerWithNotificationPermission()
            }
        } else {
            binding.text1.text = "User denied permission"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            // 通知权限被拒不影响服务本身，照常启动
            startHttpServer()
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

    // ---- HTTP 服务开关 ----

    private fun toggleHttpServer() {
        if (ProxyHttpService.running) {
            stopService(Intent(this, ProxyHttpService::class.java))
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
                    startHttpServerWithNotificationPermission()
                }
            }
        }
        dialog.show()
    }

    private fun startHttpServerWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATION)
        } else {
            startHttpServer()
        }
    }

    private fun startHttpServer() {
        try {
            val intent = Intent(this, ProxyHttpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // onCreate 是异步的，稍等再刷新按钮状态
            binding.buttonHttpToggle.postDelayed({ updateHttpStatus() }, 500)
        } catch (tr: Throwable) {
            binding.text3.text = Log.getStackTraceString(tr)
        }
    }

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
}
