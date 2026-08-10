package rikka.shizuku.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

import rikka.shizuku.Shizuku
import rikka.shizuku.demo.util.ProxyHistory
import rikka.shizuku.demo.util.SettingsGlobalUtils

/**
 * 长按主界面图标出现的 App Shortcuts 的入口：
 * 无界面（Theme.NoDisplay），解析 intent action 后切换 http_proxy，再立即 finish。
 * 首次使用如果还没授予 Shizuku/Sui 权限，会先弹授权确认框。
 */
class ProxyShortcutActivity : Activity() {

    companion object {
        const val ACTION_SET_PROXY = "rikka.shizuku.demo.action.SET_PROXY"
        const val ACTION_CLEAR_PROXY = "rikka.shizuku.demo.action.CLEAR_PROXY"

        private const val REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Shizuku.isPreV11()) {
            toast("Shizuku pre-v11 is not supported")
            finish()
            return
        }

        try {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                handle(intent)
                finish()
            } else {
                // 首次使用：先请求权限，结果通过 Shizuku 的回调监听器返回
                Shizuku.addRequestPermissionResultListener(::onPermissionResult)
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (tr: Throwable) {
            toast(tr.message)
            finish()
        }
    }

    private fun onPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQUEST_CODE) {
            Shizuku.removeRequestPermissionResultListener(::onPermissionResult)
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                handle(intent)
            } else {
                toast("用户拒绝了 Shizuku 权限")
            }
            finish()
        }
    }

    private fun handle(intent: Intent?) {
        val action = intent?.action
        try {
            when (action) {
                ACTION_SET_PROXY -> {
                    val proxy = ProxyHistory.getLast(this)
                    if (proxy == null) {
                        toast("尚未设置过代理地址，请先在应用内设置")
                        return
                    }
                    SettingsGlobalUtils.putGlobal("http_proxy", proxy)
                    toast("代理已开启: $proxy")
                }
                ACTION_CLEAR_PROXY -> {
                    SettingsGlobalUtils.putGlobal("http_proxy", ":0")
                    toast("代理已关闭")
                }
                else -> toast("未知操作: $action")
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            toast(tr.message)
        }
    }

    private fun toast(msg: String?) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }
}
