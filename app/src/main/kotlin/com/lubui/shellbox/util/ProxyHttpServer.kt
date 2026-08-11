package com.lubui.shellbox.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

import org.json.JSONException
import org.json.JSONObject

import rikka.shizuku.Shizuku

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 极简 HTTP 服务：让电脑/局域网设备通过 HTTP 请求切换系统代理。
 *
 * - 仅用 JDK 的 [ServerSocket]，不引入任何第三方依赖。
 * - 绑定 0.0.0.0，同时支持两种访问方式：
 *   1. 同一 WiFi：`http://<手机IP>:16888`（需 token 认证）；
 *   2. USB：`adb reverse tcp:16888 tcp:16888` 后访问 `http://127.0.0.1:16888`。
 * - 所有 /proxy 请求都需要 token 认证（`Authorization: Bearer <token>` 或 `?token=`），
 *   否则局域网内任何人都能改代理。
 * - 写代理复用 [SettingsGlobalUtils]（以 shell/root 身份），写历史复用 [ProxyHistory]。
 *
 * 路由：
 * ```
 * GET    /proxy                             -> {"enabled":true,"proxy":"host:port"}
 * PUT|POST /proxy  {"proxy":"host:port"}    -> 开启代理
 *           或 ?proxy=host:port
 * DELETE /proxy                             -> 关闭代理（写 ":0"）
 * GET    /clipboard                         -> 读取剪贴板 {"text":"..."}
 * PUT|POST /clipboard {"text":"..."}        -> 写入剪贴板（或 ?text=...）
 * GET    /                                  -> 帮助页（免认证；只显示 token 前 4 位，不泄露完整凭证）
 * ```
 * 所有错误响应（401/403/404/405/500）统一为 JSON：`{"error":"..."}`。
 * 剪贴板读写见 [ClipboardApi]：写入任何状态都允许；读取在 Android 10+ 需要窗口焦点，
 * 通过透明 [ClipboardGhostActivity] 抢焦点实现（前台服务本身无焦点）。
 */
class ProxyHttpServer private constructor(
    private val context: Context,
    private val port: Int,
    private val token: String
) {

    companion object {
        private const val TAG = "ProxyHttpServer"

        /** 默认监听端口；启动时可在应用内自定义并持久化。 */
        const val DEFAULT_PORT = 16888

        private const val PREFS = "http_server"
        private const val KEY_TOKEN = "token"
        private const val KEY_PORT = "port"
        private const val KEY_AUTO_START = "auto_start"

        /** 当前配置的监听端口（SharedPreferences 持久化，默认 [DEFAULT_PORT]）。 */
        fun getPort(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)
        }

        /** 保存自定义监听端口。 */
        fun setPort(context: Context, port: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PORT, port.coerceIn(1, 65535))
                .apply()
        }

        /** 是否开机自动启动 HTTP 服务（默认关，需在应用内手动打开）。 */
        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)
        }

        /** 设置开机自动启动。 */
        fun setAutoStartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .apply()
        }

        /**
         * 获取访问令牌；首次调用时生成并持久化（SharedPreferences）。
         * token 会在应用 UI 和通知里展示，电脑端用它做 Bearer 认证。
         */
        fun getToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_TOKEN, null)?.let { if (it.isNotEmpty()) return it }
            val token = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            prefs.edit().putString(KEY_TOKEN, token).apply()
            return token
        }

        /** 当前局域网 IPv4 地址（用于展示访问 URL）；获取失败返回 null。 */
        fun getLocalIpAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        /** 创建并启动服务（token、端口均取持久化的值）。 */
        fun create(context: Context): ProxyHttpServer {
            return ProxyHttpServer(context.applicationContext, getPort(context), getToken(context))
        }
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val lock = Any()

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    /** 启动监听；端口被占用等失败时返回 false。 */
    fun start(): Boolean {
        synchronized(lock) {
            if (running) return true
            return try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                running = true
                acceptThread = Thread({ acceptLoop(ss) }, "proxy-http-accept").apply { start() }
                Log.i(TAG, "HTTP server listening on port $port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "bind failed on port $port", e)
                false
            }
        }
    }

    /** 停止监听，释放端口。 */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {
            }
            serverSocket = null
            executor.shutdownNow()
            Log.i(TAG, "HTTP server stopped")
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val socket = ss.accept()
                socket.soTimeout = 10_000
                executor.execute { handle(socket) }
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "accept error", e) // 停止时 close() 会抛这个，属正常
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept error", e)
            }
        }
    }

    // ---- 请求处理 ----

    private class Response(val code: Int, val contentType: String, val body: String) {
        fun write(socket: Socket) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code ${reasonPhrase(code)}\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "\r\n"
            socket.getOutputStream().run {
                write(head.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        }

        private fun reasonPhrase(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                // 请求头
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

                // curl 对 >1KB 的 body 会先发 Expect: 100-continue 等服务端确认，
                // 不回 100 会一直等不到 body（表现为请求卡住直到超时）
                if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                    socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }

                // 请求体：循环读完当前可读数据（read 单次可能只返回一部分；
                // ready() 判停避免 UTF-8 下字符数<字节数时阻塞等不存在的剩余字节）
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var off = 0
                    while (off < contentLength && reader.ready()) {
                        val n = reader.read(buf, off, contentLength - off)
                        if (n <= 0) break // EOF
                        off += n
                    }
                    String(buf, 0, off)
                } else ""

                // 路径 + query
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                // 认证：Bearer 头或 ?token= 都行
                val authHeader = headers["authorization"] ?: ""
                val bearer = if (authHeader.startsWith("Bearer ", ignoreCase = true)) authHeader.substring(7).trim() else ""
                val authed = bearer == token || (query["token"] ?: "") == token

                route(method, path, query, body, authed).write(socket)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handle error", e)
            try {
                errorResponse(500, "internal error: ${e.message}").write(socket)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun route(method: String, path: String, query: Map<String, String>, body: String, authed: Boolean): Response {
        if (path == "/") {
            return Response(200, "text/plain", helpText())
        }
        if (path != "/proxy" && path != "/clipboard") {
            return errorResponse(404, "not found: $path")
        }
        if (!authed) {
            return errorResponse(401, "unauthorized: missing or wrong token")
        }
        return if (path == "/proxy") routeProxy(method, query, body) else routeClipboard(method, query, body)
    }

    /** /proxy：读写全局代理（需 Shizuku/Sui 授权）。 */
    private fun routeProxy(method: String, query: Map<String, String>, body: String): Response {
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
        if (!granted) {
            return errorResponse(403, "Shizuku/Sui permission not granted")
        }

        return try {
            when (method) {
                "GET" -> {
                    val proxy = SettingsGlobalUtils.getGlobal("http_proxy")
                    val enabled = !proxy.isNullOrEmpty() && proxy != ":0"
                    Response(200, "application/json", jsonObject(
                        "enabled" to enabled,
                        "proxy" to (proxy ?: JSONObject.NULL) // NULL 哨兵：保持输出显式 null 而非缺字段
                    ) + "\n")
                }
                "PUT", "POST" -> {
                    val value = query["proxy"] ?: parseJsonBodyField(body, "proxy") ?: parseQuery(body)["proxy"]
                    if (value.isNullOrEmpty() || value.indexOf(':') == -1) {
                        errorResponse(400, "invalid proxy, expected \"host:port\", got: $value")
                    } else {
                        SettingsGlobalUtils.putGlobal("http_proxy", value)
                        ProxyHistory.add(context, value)
                        Response(200, "application/json", jsonObject(
                            "ok" to true,
                            "proxy" to value
                        ) + "\n")
                    }
                }
                "DELETE" -> {
                    SettingsGlobalUtils.putGlobal("http_proxy", ":0")
                    Response(200, "application/json", jsonObject(
                        "ok" to true,
                        "enabled" to false
                    ) + "\n")
                }
                else -> errorResponse(405, "method not allowed: $method")
            }
        } catch (tr: Throwable) {
            Log.e(TAG, "route error", tr)
            errorResponse(500, "error: ${tr.message}")
        }
    }

    /** /clipboard：读写剪贴板（普通 API，不需要 Shizuku；读取限制见 [ClipboardApi] 注释）。 */
    private fun routeClipboard(method: String, query: Map<String, String>, body: String): Response {
        return try {
            when (method) {
                "GET" -> {
                    val text = ClipboardApi.getText(context)
                    if (text == null) {
                        // 区分失败原因，给出可操作提示
                        val hint = if (Settings.canDrawOverlays(context)) {
                            "no window focus (screen locked?)"
                        } else {
                            "background launch blocked; grant \"Display over other apps\" (悬浮窗) permission in settings"
                        }
                        errorResponse(500, "clipboard read failed: $hint")
                    } else {
                        Response(200, "application/json", jsonObject(
                            "text" to text
                        ) + "\n")
                    }
                }
                "PUT", "POST" -> {
                    val value = query["text"] ?: parseJsonBodyField(body, "text") ?: parseQuery(body)["text"]
                    if (value == null) {
                        errorResponse(400, "missing text: expected {\"text\":\"...\"} or ?text=...")
                    } else {
                        ClipboardApi.setText(context, value)
                        Response(200, "application/json", jsonObject(
                            "ok" to true,
                            "text" to value
                        ) + "\n")
                    }
                }
                else -> errorResponse(405, "method not allowed: $method")
            }
        } catch (tr: Throwable) {
            Log.e(TAG, "clipboard route error", tr)
            errorResponse(500, "error: ${tr.message}")
        }
    }

    private fun helpText(): String = buildString {
        append("ShellBox control\n")
        // 帮助页免认证，绝不能泄露完整 token（否则局域网内任何人 curl / 即可拿到凭证改代理/读剪贴板）；
        // 只显示前 4 位供确认服务器身份，完整 token 见手机端应用界面/常驻通知。
        append("Token: ").append(token.take(4)).append("**** (full token: see app UI / notification on the phone)\n\n")
        append("All /proxy and /clipboard requests need auth:\n")
        append("  header: Authorization: Bearer <token>\n")
        append("  or query: ?token=<token>\n\n")
        append("Endpoints:\n")
        append("  GET    /proxy                          -> {\"enabled\":true,\"proxy\":\"host:port\"}\n")
        append("  PUT    /proxy {\"proxy\":\"host:port\"}  -> enable proxy\n")
        append("  POST   /proxy ?proxy=host:port         -> enable proxy (query form)\n")
        append("  DELETE /proxy                          -> disable proxy\n")
        append("  GET    /clipboard                      -> {\"text\":\"...\"} read clipboard\n")
        append("  PUT|POST /clipboard {\"text\":\"...\"}  -> set clipboard\n")
        append("           or ?text=...                  -> set clipboard (query form)\n")
        append("\nAll errors (401/403/404/405/500) are JSON: {\"error\":\"...\"}\n")
        append("\nClipboard notes:\n")
        append("  - GET needs screen on & unlocked (Android 10+ requires window focus);\n")
        append("  - background GET additionally needs the \"Display over other apps\" (悬浮窗)\n")
        append("    permission — enable it in the app to allow background activity launches.\n")
        append("  - Android 12+ shows a system toast on every clipboard read.\n")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isEmpty()) return map
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                map[pair.substring(0, idx)] = decodeQueryValue(pair.substring(idx + 1))
            }
        }
        return map
    }

    /** URL 解码 query 值（%XX 与 +）；非法转义时原样返回。 */
    private fun decodeQueryValue(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (e: Exception) {
            value
        }
    }

    /**
     * 从 JSON body 提取字段值（改用平台自带的 org.json 解析，替代手写正则；非法 JSON/缺字段返回 null）。
     * 值支持完整 JSON 转义（含 `\uXXXX`），数组/嵌套对象等由调用方决定是否接受（本服务取字符串字段）。
     */
    private fun parseJsonBodyField(body: String, field: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = JSONObject(body)
            if (!obj.has(field) || obj.isNull(field)) null else obj.optString(field)
        } catch (e: JSONException) {
            null
        }
    }

    /** 构造 JSON 错误响应：`{"error":"..."}`（4xx/5xx 统一用 JSON，方便脚本解析）。 */
    private fun errorResponse(code: Int, message: String): Response =
        Response(code, "application/json", jsonObject("error" to message) + "\n")

    /** 用平台 org.json 构造 JSON 对象（null 值输出为 `null` 而非缺字段；传 JSONObject.NULL 可强制显式 null）。 */
    private fun jsonObject(vararg pairs: Pair<String, Any?>): String {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v ?: JSONObject.NULL)
        return obj.toString()
    }
}
