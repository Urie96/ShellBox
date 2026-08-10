package rikka.shizuku.demo;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
import rikka.shizuku.demo.util.SettingsGlobalUtils;

/**
 * 桌面快捷方式（App Shortcuts）的入口：
 * 无界面（Theme.NoDisplay），解析 intent action 后切换 http_proxy，再立即 finish。
 * 首次使用如果还没授予 Shizuku/Sui 权限，会先弹授权确认框。
 */
public class ProxyShortcutActivity extends Activity {

    public static final String ACTION_SET_PROXY = "rikka.shizuku.demo.action.SET_PROXY";
    public static final String ACTION_CLEAR_PROXY = "rikka.shizuku.demo.action.CLEAR_PROXY";

    static final String PREFS = "proxy";
    static final String KEY_PROXY = "http_proxy";
    static final String DEFAULT_PROXY = "10.79.227.212:8080";

    private static final int REQUEST_CODE = 100;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = this::onPermissionResult;

    private void onPermissionResult(int requestCode, int grantResult) {
        if (requestCode == REQUEST_CODE) {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
            if (grantResult == PERMISSION_GRANTED) {
                handle(getIntent());
            } else {
                toast("用户拒绝了 Shizuku 权限");
            }
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Shizuku.isPreV11()) {
            toast("Shizuku pre-v11 is not supported");
            finish();
            return;
        }

        try {
            if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
                handle(getIntent());
                finish();
            } else {
                // 首次使用：先请求权限，结果通过 Shizuku 的回调监听器返回
                Shizuku.addRequestPermissionResultListener(permissionListener);
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable tr) {
            toast(tr.getMessage());
            finish();
        }
    }

    private void handle(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        try {
            if (ACTION_SET_PROXY.equals(action)) {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                String proxy = prefs.getString(KEY_PROXY, DEFAULT_PROXY);
                SettingsGlobalUtils.putGlobal("http_proxy", proxy);
                toast("代理已开启: " + proxy);
            } else if (ACTION_CLEAR_PROXY.equals(action)) {
                SettingsGlobalUtils.putGlobal("http_proxy", ":0");
                toast("代理已关闭");
            } else {
                toast("未知操作: " + action);
            }
        } catch (Throwable tr) {
            tr.printStackTrace();
            toast(tr.getMessage());
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
