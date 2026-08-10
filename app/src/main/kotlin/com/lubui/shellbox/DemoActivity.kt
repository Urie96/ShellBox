package com.lubui.shellbox

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView

import rikka.shizuku.Shizuku
import com.lubui.shellbox.databinding.MainActivityBinding
import com.lubui.shellbox.util.ProxyHistory
import com.lubui.shellbox.util.SettingsGlobalUtils

@SuppressLint("SetTextI18n")
class DemoActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_SET_PROXY = 5
        private const val REQUEST_CODE_CLEAR_PROXY = 6
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

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER)
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
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
            }
        } else {
            binding.text1.text = "User denied permission"
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
}
