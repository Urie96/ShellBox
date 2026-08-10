# AGENTS.md

面向 AI 编码助手和开发者的项目指南。修改本仓库代码前请先阅读本文。

## 项目概览

**ShellBox** — 一个 Android demo 应用，演示如何通过
[Shizuku](https://github.com/RikkaApps/Shizuku) / [Sui](https://github.com/RikkaApps/Sui)
以 [shell(uid 2000)/root(uid 0)](https://github.com/RikkaApps/Shizuku-API)
身份调用普通应用无权调用的系统能力。Sui 源码见
https://github.com/RikkaApps/Sui（子模块式结构，含 Shizuku-API）。

当前功能**只有代理开关**（其余 demo 功能已删除）：
- 以 shell/root 身份读写 `Settings.Global` 的 `http_proxy`，等价于 `settings put/get global http_proxy ...`
- 应用内「开启代理」弹对话框：列出最近使用过的代理地址（按最近使用排序，最多 10 条）+ 手动输入框；「关闭代理」写入 `":0"`
- 长按桌面图标快捷方式：一键开启/关闭代理（开启时默认使用最近一次使用的地址）

## 技术栈与版本

| 项 | 值 |
|---|---|
| Gradle | 8.14.4（**系统 PATH，无 gradle wrapper**） |
| AGP | 8.10.1（`com.android.application`） |
| Kotlin | 2.0.21（`org.jetbrains.kotlin.android`，项目为纯 Kotlin） |
| Refine | `dev.rikka.tools.refine` 4.4.0（hidden API 编译期变换） |
| Shizuku API | `dev.rikka.shizuku:api` / `provider` 13.1.5（Maven） |
| Hidden stub | `dev.rikka.hidden:stub` 4.4.0（compileOnly） |
| Hidden 豁免 | `org.lsposed.hiddenapibypass:hiddenapibypass` 6.1 |
| SDK / 目标 | compileSdk 36, minSdk 24, targetSdk 36 |
| JVM | source/target 21，`kotlinOptions.jvmTarget = '21'` |
| 开发机 | Android 14 (API 34) |

## 构建与部署

```bash
# 构建（gradle 已在 PATH，无需 wrapper / devenv shell）
gradle :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk

# 部署（设备需开启 USB 调试）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

注意：
- **必须用系统 `gradle` 命令**（8.14.4），仓库没有 `gradlew`，不要创建 wrapper。
- `local.properties` 由 nix-shell/devenv 自动生成（`sdk.dir`/`ndk.dir` 指向 nix store），
  不要提交、不要手动改。
- debug 构建也启用了 R8 裁剪（只 shrink 不混淆，堆栈可读），APK 约 70KB；
  若发现 APK 异常变大（几 MB 的零填充垃圾），是增量打包残留，`gradle clean` 后重建即可。

## 项目结构

```
app/src/main/
├── kotlin/com/lubui/shellbox/
│   ├── DemoActivity.kt            # 主界面（viewBinding: MainActivityBinding），仅开启/关闭代理
│   ├── DemoApplication.kt         # Sui.init + HiddenApiBypass 全局豁免
│   ├── ProxyShortcutActivity.kt   # 长按快捷方式入口（Theme.NoDisplay，无界面）
│   └── util/
│       ├── SettingsGlobalUtils.kt # ★ 核心：以 shell/root 读写 Settings.Global
│       └── ProxyHistory.kt        # 最近使用的代理地址历史（SharedPreferences，最多 10 条）
└── res/
    ├── xml/shortcuts.xml          # 静态快捷方式（长按菜单：开启/关闭代理）
    ├── layout/main_activity.xml   # 主界面：开启代理 / 关闭代理 两个按钮 + 状态文本
    ├── layout/dialog_proxy.xml    # 开启代理对话框：历史地址列表 + 手动输入框
    └── drawable/ic_proxy_on|off.xml
```

## 关键机制：SettingsGlobalUtils（代理读写）

普通应用调 `Settings.Global.putString` 会因缺少 `WRITE_SECURE_SETTINGS` 抛
SecurityException。该工具类让调用以 shell/root 身份执行：

1. `IActivityManager` 包 `ShizukuBinderWrapper` → 以 shell/root 调
   `getContentProviderExternal("settings", 0, null, "settings")` 拿到 settings provider；
2. provider binder 再包一层 `ShizukuBinderWrapper` → 后续 `call()` 也以 shell/root 执行；
3. 调 SettingsProvider 的 call 方法 `PUT_global` / `GET_global` 读写。

**版本约束：只支持 Android 12+（API 31+）**——`call()` 用 `AttributionSource` 参数
（开发机 API 34）。历史版本分支已被按需求移除，不要加回。

## 代理历史：ProxyHistory

- 存于 SharedPreferences（`PREFS="proxy"`），`KEY_HISTORY="proxy_history"` 以换行分隔
  存储历史列表（最新在前，最多 10 条）；`add()` 去重并移到最前。
- 兼容旧版本：旧 key `http_proxy`（只存最后一个地址）首次读取时自动迁移。
- 应用内「开启代理」对话框展示 `getHistory()`；长按快捷方式「开启代理」用 `getLast()`，
  无历史时提示「尚未设置过代理地址」。
- 清除代理 = 写入 `":0"`（惯例写法，勿用空串）。

## 权限/交互流程

- 所有 Shizuku 操作先走 `checkPermission(code)`（DemoActivity）：未授权则
  `Shizuku.requestPermission()` → 系统弹 Sui/Shizuku 确认框 → 回调
  `Shizuku.OnRequestPermissionResultListener`（**不是** Activity 的
  `onRequestPermissionsResult`）。
- 快捷方式入口（ProxyShortcutActivity）首次使用同样会请求授权。

## 代码约定

- 纯 Kotlin（勿新建 `.java`）。
- 隐藏 API 类（`android.content.IContentProvider`、`android.app.IActivityManager` 等）
  直接以类型引用：compileOnly hidden stub 负责编译期，运行时靠
  `DemoApplication` 里的 `HiddenApiBypass.addHiddenApiExemptions("L")`。
- 不要引入额外依赖；若确需，遵循 Maven 坐标 + 版本与上表一致。

## 已知坑（改代码前必读）

1. **`res/xml` 里不能用 `${applicationId}` 占位符**——AAPT2 不替换资源 XML 中的占位符
   （那是 manifest merger 的功能）。shortcuts.xml 的 `targetPackage` 必须硬编码
   `com.lubui.shellbox`。
2. **Android 14+ 没有 `IContentProvider$Stub` 类**——IContentProvider 从 AIDL 接口改为
   普通 Java 接口，客户端代理要用 `ContentProviderNative.asInterface()`（反射）。
   `Class.forName("android.content.IContentProvider$Stub")` 在 API 34 会抛
   ClassNotFoundException。
3. **`AttributionSource(int, String, String)` 构造器是隐藏 API**——用公开的
   `AttributionSource.Builder(uid).setPackageName(pkg).build()`，uid 用
   `Shizuku.getUid()` 使其与真实 binder 调用者（shell/root）一致。
4. **快捷方式/固定桌面**：不要用 `requestPinShortcut`（Android 13+ 的
   `INSTALL_SHORTCUT` 权限在 MIUI/HyperOS 上授予后仍会失败）。当前方案是纯静态
   App Shortcuts（长按菜单），不涉及任何额外权限。
5. **不要重新引入版本兼容分支**——SettingsGlobalUtils 已按需求裁剪为 API 31+ 单路径。
6. **`gradle :app:assembleDebug` 是唯一可靠构建入口**；（gradle 本身已在 PATH）。

## 相关资源

- Shizuku-API 源码/文档：https://github.com/RikkaApps/Shizuku-API（v13.x）
- Shizuku-API issue #12（以 shell 身份调 Settings 的官方做法）：
  https://github.com/RikkaApps/Shizuku-API/issues/12
- 上游 Sui 项目：https://github.com/RikkaApps/Sui
- 本 demo 使用的依赖均来自 Maven Central：`dev.rikka.shizuku:api:13.1.5` 等
