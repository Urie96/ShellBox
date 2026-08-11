# ShellBox

个人自用的 Android 小工具应用，通过 [Shizuku](https://github.com/RikkaApps/Shizuku) / [Sui](https://github.com/RikkaApps/Sui)
以 **shell(uid 2000) / root(uid 0)** 身份执行普通应用无权调用的系统操作。

当前功能：**一键开关全局 HTTP 代理**（读写 `Settings.Global` 的 `http_proxy`，等价于 `settings put/get global http_proxy ...`）。
后续计划在这个框架下继续加同类小功能（以 shell/root 身份干活的各种开关/设置）。

## 功能

- **开启代理**：弹对话框，列出最近使用过的代理地址（按最近使用排序，最多保留 10 条），可点击历史地址或手动输入，确认后写入 `http_proxy`
- **关闭代理**：写入 `":0"`（惯例写法）
- **长按桌面图标快捷方式**：一键开启/关闭代理；开启时默认使用最近一次使用的地址
- 代理历史保存在本地 SharedPreferences，兼容旧版本（旧 key 自动迁移）
- **HTTP 远程控制**：电脑通过 HTTP 读写剪贴板（`GET/PUT /clipboard`）与开关代理（`/proxy`），
  详见 [AGENTS.md](AGENTS.md)「HTTP 远程控制」章节

## 环境要求

- Android 12+（API 31+）：`Settings.Global` 读写依赖 API 31 的 `AttributionSource` 参数
- 设备需安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 或 [Sui](https://github.com/RikkaApps/Sui)（Magisk 模块）
- 首次使用会弹出 Shizuku/Sui 授权确认框

## 构建

```bash
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 70KB）

> 系统 `gradle` 8.14.4 已在 PATH，仓库无 gradle wrapper。debug 构建也启用了 R8 裁剪
> （只裁剪不混淆，堆栈可读）；若 APK 异常变大，`gradle clean` 后重建即可。

## 安装与使用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **日常直接用 debug 包即可**：自动用 debug keystore 签名，可直接安装；与 release 的唯一区别是
  `debuggable=true`（可调试、可看完整日志）和体积略大，功能完全一致。
- 安装后打开 App → 首次点击「开启代理」会请求 Shizuku/Sui 授权 → 授权后选择/输入代理地址即可。
- 长按桌面图标可快速开启/关闭代理。

## 工作原理

普通应用调 `Settings.Global.putString` 会因缺少 `WRITE_SECURE_SETTINGS` 权限抛
SecurityException。ShellBox 的做法（Shizuku-API issue #12 的官方方案）：

1. 用 `ShizukuBinderWrapper` 包装 `IActivityManager`，以 shell/root 身份调用
   `getContentProviderExternal("settings", ...)` 获取 settings provider；
2. provider binder 再包一层 `ShizukuBinderWrapper`，使后续 `call()` 同样以 shell/root 身份执行；
3. 调用 SettingsProvider 的 `PUT_global` / `GET_global` 读写全局设置。

核心代码见 `app/src/main/kotlin/com/lubui/shellbox/util/SettingsGlobalUtils.kt`。

## 项目结构

```
app/src/main/
├── kotlin/com/lubui/shellbox/
│   ├── DemoActivity.kt            # 主界面：开启/关闭代理 + 权限处理
│   ├── DemoApplication.kt         # Sui.init + HiddenApiBypass 全局豁免
│   ├── ProxyShortcutActivity.kt   # 长按快捷方式入口（Theme.NoDisplay，无界面）
│   ├── ClipboardGhostActivity.kt  # 剪贴板读取用透明幽灵 Activity（抢焦点读后立即关闭）
│   └── util/
│       ├── SettingsGlobalUtils.kt # ★ 核心：以 shell/root 读写 Settings.Global
│       ├── ProxyHistory.kt        # 最近使用的代理地址历史
│       ├── ProxyHttpServer.kt     # ★ 极简 HTTP 服务器（纯 JDK ServerSocket，零依赖）
│       └── ClipboardApi.kt        # 剪贴板读写：写入直接写；读取 Android 10+ 走 ghost activity
└── res/
    ├── xml/shortcuts.xml          # 静态快捷方式（长按菜单：开启/关闭代理）
    ├── layout/main_activity.xml   # 主界面
    ├── layout/dialog_proxy.xml    # 开启代理对话框（历史列表 + 输入框）
    ├── values/styles.xml          # Theme.ShellBox.Transparent（幽灵 Activity 用）
    ├── mipmap*/ic_launcher.xml    # 应用图标（>_ 终端提示符风格）
    └── drawable/                  # 快捷方式图标、图标前景
```

## 技术栈

| 项 | 值 |
|---|---|
| Gradle / AGP | 8.14.4 / 8.10.1 |
| Kotlin | 2.0.21（纯 Kotlin） |
| Shizuku API | `dev.rikka.shizuku:api` / `provider` 13.1.5 |
| Hidden stub | `dev.rikka.hidden:stub` 4.4.0（compileOnly，hidden API 编译期类型） |
| Hidden 豁免 | `org.lsposed.hiddenapibypass:hiddenapibypass` 6.1 |
| SDK | compileSdk 36, minSdk 24, targetSdk 36 |
| JVM | 21 |

## 注意事项

- **改包名/卸载会清空代理历史**（SharedPreferences 随应用数据），换包名后需重新授权 Shizuku/Sui
- `res/xml/shortcuts.xml` 中的 `targetPackage` 是硬编码的（AAPT2 不替换资源 XML 占位符），改包名时要同步更新
- 清除代理的惯例写法是写入 `":0"`，不要用空串
- 开发细节与已知坑见 [AGENTS.md](AGENTS.md)

## 相关资源

- [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)（v13.x）
- [Shizuku-API issue #12](https://github.com/RikkaApps/Shizuku-API/issues/12)：以 shell 身份调 Settings 的官方做法
- [Sui](https://github.com/RikkaApps/Sui)
