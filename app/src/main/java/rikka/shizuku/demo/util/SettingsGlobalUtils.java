package rikka.shizuku.demo.util;

import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;
/**
 * 以 Shizuku/Sui 的 shell(root) 身份读写 {@link Settings.Global}，
 * 等价于 shell 命令 `settings put/get global &lt;name&gt; &lt;value&gt;`。
 *
 * 仅支持 Android 12+（API 31+）：该版本起 IContentProvider.call 使用
 * AttributionSource 参数（开发机为 Android 14 / API 34）。
 *
 * 为什么不能直接调 {@link Settings.Global#putString}：写 global 设置要求
 * WRITE_SECURE_SETTINGS（signature|privileged）权限，普通应用没有，会抛
 * SecurityException；而 Shizuku 进程以 shell(uid 2000) 运行、Sui 以 root(uid 0)
 * 运行，都拥有该权限。
 *
 * 原理（Shizuku-API issue #12 的官方做法）：
 * 1. 把 IActivityManager 用 {@link ShizukuBinderWrapper} 包装，以 shell/root
 *    身份调用 getContentProviderExternal("settings", ...) 拿到 settings provider；
 * 2. 再把 provider binder 也用 {@link ShizukuBinderWrapper} 包装，使 call() 同样
 *    以 shell/root 身份执行，通过 SettingsProvider 的 WRITE_SECURE_SETTINGS 检查；
 * 3. 调用 SettingsProvider 的 call 方法 "PUT_global"/"GET_global" 读写全局设置。
 */
public class SettingsGlobalUtils {

    private static final String SETTINGS_AUTHORITY = "settings";
    private static final String CALLING_PACKAGE = "com.android.shell";
    private static final String CALL_METHOD_GET_GLOBAL = "GET_global";
    private static final String CALL_METHOD_PUT_GLOBAL = "PUT_global";

    private static final String KEY_VALUE = "value";

    /**
     * 构造调用方的 AttributionSource。uid 取 Shizuku 进程的真实 uid（shell 2000 / root 0），
     * 使其与实际 binder 调用者一致，避免归因检查不匹配。
     * （AttributionSource 的 (int, String, String) 构造器是隐藏 API，这里用公开的 Builder。）
     */
    private static AttributionSource newAttributionSource() {
        return new AttributionSource.Builder(Shizuku.getUid())
                .setPackageName(CALLING_PACKAGE)
                .build();
    }

    /**
     * 读取全局设置，等价于 `settings get global &lt;name&gt;`。
     *
     * @return 设置值；未设置时返回 null
     */
    public static String getGlobal(String name) throws Exception {
        IContentProvider provider = acquireSettingsProvider();
        try {
            Bundle reply = provider.call(
                    newAttributionSource(),
                    SETTINGS_AUTHORITY, CALL_METHOD_GET_GLOBAL, name, null);
            return reply == null ? null : reply.getString(KEY_VALUE);
        } finally {
            releaseSettingsProvider();
        }
    }

    /**
     * 写入全局设置，等价于 `settings put global &lt;name&gt; &lt;value&gt;`。
     * 清除 http_proxy 的惯例写法是 value = {@code ":0"}。
     */
    public static void putGlobal(String name, String value) throws Exception {
        IContentProvider provider = acquireSettingsProvider();
        try {
            Bundle args = new Bundle();
            args.putString(KEY_VALUE, value);
            provider.call(
                    newAttributionSource(),
                    SETTINGS_AUTHORITY, CALL_METHOD_PUT_GLOBAL, name, args);
        } finally {
            releaseSettingsProvider();
        }
    }

    /**
     * 以 shell/root 身份获取 "settings" provider，并包装为 IContentProvider。
     */
    private static IContentProvider acquireSettingsProvider() throws Exception {
        IActivityManager am = IActivityManager.Stub.asInterface(
                new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")));
        ContentProviderHolder holder = am.getContentProviderExternal(SETTINGS_AUTHORITY, 0, null, SETTINGS_AUTHORITY);
        if (holder == null || holder.provider == null) {
            throw new IllegalStateException("Cannot acquire settings provider");
        }

        // Android 14 (API 34) 起 IContentProvider 不再是 AIDL 接口，已没有
        // IContentProvider$Stub 类；客户端代理由 ContentProviderNative.asInterface() 创建。
        // （该处仅剩的反射是因为 hidden stub 库不包含这些类。）
        Class<?> nativeClass = Class.forName("android.content.ContentProviderNative");
        Method asInterface = nativeClass.getMethod("asInterface", IBinder.class);
        return (IContentProvider) asInterface.invoke(null, new ShizukuBinderWrapper(holder.provider.asBinder()));
    }

    private static void releaseSettingsProvider() throws RemoteException {
        IActivityManager am = IActivityManager.Stub.asInterface(
                new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")));
        am.removeContentProviderExternal(SETTINGS_AUTHORITY, null);
    }
}
