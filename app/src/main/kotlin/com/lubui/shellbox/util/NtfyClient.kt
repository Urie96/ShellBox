package com.lubui.shellbox.util

import android.util.Log

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import org.json.JSONObject

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ntfy 客户端：长轮询订阅 + 发布。协议见 https://docs.ntfy.sh
 *
 * 订阅本质是「打开一条 GET 长连接，服务器逐行吐 JSON 事件」：
 * ```
 * GET {server}/{topic}/json?since=<id|时间戳>
 * {"event":"open",...}
 * {"event":"message","id":"k8sft6vb-1qfc0tkr1h6","title":"CI","message":"build #123 passed","priority":4,"tags":["rocket"],"click":"https://..."}
 * {"event":"keepalive",...}   // 每 ~45s 一条，用于保活
 * ```
 * - 认证：访问令牌（tk_xxx）经 `Authorization: Bearer <token>`，发布/订阅通用。
 * - 断线由 [startSubscribe] 内部指数退避自动重连（1s→…→60s），并用 `since=<lastId>` 补收
 *   离线期间错过的消息（ntfy 服务器默认缓存 12h）。「重连后只收新消息」用 `since=<当前unix秒>`。
 * - [reconnectNow] 供网络恢复回调调用：取消在途请求并唤醒退避睡眠，立即重试。
 *
 * 本类不依赖任何第三方推送服务；服务器/topic/token 由 [PushConfig] 提供。
 */
object NtfyClient {

    private const val TAG = "NtfyClient"

    /** 订阅到的消息（ntfy message 事件）。 */
    data class Message(
        val id: String,
        val title: String?,
        val body: String,
        val priority: Int,
        val tags: List<String>,
        val click: String?
    )

    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L

    /** 强制重连（reconnectNow）的最小间隔：防止网络回调/连接抖动打出请求风暴烧掉服务器限流配额。 */
    private const val RECONNECT_THROTTLE_MS = 5_000L

    /** 收到 429（限流）后的固定长退避：配额恢复需要时间，短间隔重试只会火上浇油。 */
    private const val RATE_LIMIT_BACKOFF_MS = 5 * 60_000L

    /** 单次读超时：必须大于 ntfy keepalive 间隔（~45s），否则会误判死连接。 */
    private const val READ_TIMEOUT_SECONDS = 90L

    /**
     * HTTP 客户端。**强制 HTTP/1.1**：长轮询订阅是无限流式响应，经 nginx 反代时 HTTP/2 的
     * 流式转发可能被缓冲导致消息收不到（实测 curl HTTP/1.1 正常、OkHttp 默认 HTTP/2 收不到）；
     * 长轮询用 1.1 本来就够了。
     */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ntfy-subscribe").apply { isDaemon = true }
    }

    @Volatile
    private var running = false
    private var currentCall: okhttp3.Call? = null
    private var sleepThread: Thread? = null

    /** 会话 id：每次 startSubscribe 自增；旧会话退出时不能误清新会话的 running 标志。 */
    private var session = 0

    /** 上次强制重连的时间戳（reconnectNow 节流用）。 */
    @Volatile
    private var lastForcedReconnect = 0L

    val isRunning: Boolean get() = running

    /**
     * 启动订阅循环（异步，跑在独立线程；重复调用直接返回）。
     *
     * @param initialSince 首次连接的 `since` 值：传 unix 秒级时间戳表示「只收此刻之后的消息」
     *   （首次安装不想把服务器缓存的 12h 历史全弹出来）；循环内部收到消息后自动改用消息 id 续传。
     * @param onMessage 收到消息回调（订阅线程）。
     * @param onError 连接/认证错误回调（订阅线程，指数退避期间每次失败都回调）。
     * @param onConnected 每次成功建立连接后回调（订阅线程；用于清掉 UI 上残留的旧错误）。
     */
    fun startSubscribe(
        server: String,
        topic: String,
        token: String,
        initialSince: String,
        onMessage: (Message) -> Unit,
        onError: (String) -> Unit,
        onConnected: () -> Unit
    ) {
        val mySession: Int
        synchronized(this) {
            if (running) return
            running = true
            mySession = ++session
        }
        executor.execute {
            var since: String? = initialSince
            var backoff = INITIAL_BACKOFF_MS
            var rateLimited = false
            while (running) {
                try {
                    val url = subscribeUrl(server, topic, since)
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            if (token.isNotBlank()) header("Authorization", "Bearer $token")
                            header("User-Agent", "ShellBox")
                        }
                        .build()
                    val call = http.newCall(request)
                    currentCall = call
                    call.execute().use { response ->
                        currentCall = null
                        if (!response.isSuccessful) {
                            val friendly = when (response.code) {
                                401, 403 -> "认证失败（HTTP ${response.code}）：请检查 token"
                                429 -> "被限流（HTTP 429）：请求过多，正在自动退避重试"
                                else -> "服务器返回 HTTP ${response.code}"
                            }
                            rateLimited = response.code == 429
                            throw IOException(friendly)
                        }
                        // 连接成功：重置退避 + 通知上层
                        backoff = INITIAL_BACKOFF_MS
                        onConnected()
                        // 注意：必须直接用 response.body.source()（BufferedSource，会做网络 I/O）；
                        // 不要调 .buffer()——那会拿到内部 Buffer，读它不做 I/O，延迟下读到的永远是空 → 立即 EOF
                        val source = response.body?.source()
                        while (running && source != null) {
                            val line = source.readUtf8Line() ?: break
                            val msg = parseLine(line) ?: continue
                            onMessage(msg)
                            if (msg.id.isNotEmpty()) since = msg.id
                        }
                    }
                } catch (tr: Throwable) {
                    if (!running) break
                    val err = tr.message ?: tr.javaClass.simpleName
                    Log.w(TAG, "subscribe error: $err")
                    onError(err)
                }
                if (!running) break
                if (rateLimited) {
                    // 限流：固定长退避（5 分钟），期间不再发任何请求
                    rateLimited = false
                    sleep(RATE_LIMIT_BACKOFF_MS)
                } else {
                    sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            synchronized(this) {
                if (mySession == session) {
                    running = false
                    currentCall = null
                }
            }
        }
    }

    /** 停止订阅：取消在途请求并唤醒退避睡眠，循环在下一轮检查退出。 */
    fun stopSubscribe() {
        running = false
        currentCall?.cancel()
        sleepThread?.interrupt()
    }

    /**
     * 网络恢复等场景下立即重试（取消在途请求 + 唤醒退避睡眠）。
     * 带节流：5 秒内只生效一次，防止 onAvailable 连发/网络抖动把服务器打出限流风暴。
     */
    fun reconnectNow() {
        val now = System.currentTimeMillis()
        if (now - lastForcedReconnect < RECONNECT_THROTTLE_MS) return
        lastForcedReconnect = now
        currentCall?.cancel()
        sleepThread?.interrupt()
    }

    /**
     * 发布一条消息（阻塞，可抛 [IOException]/[RuntimeException]）。测试推送用。
     *
     * @param priority ntfy 优先级 1-5（1=min, 3=default, 5=urgent）。
     */
    @Throws(IOException::class)
    fun publish(server: String, topic: String, token: String, title: String, message: String, priority: Int) {
        val json = JSONObject()
            .put("topic", topic)
            .put("message", message)
            .put("priority", priority)
        if (title.isNotBlank()) json.put("title", title)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("${server.trimEnd('/')}/$topic")
            .post(body)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("发布失败（HTTP ${response.code}）")
            }
        }
    }

    private fun subscribeUrl(server: String, topic: String, since: String?): String {
        val base = server.trimEnd('/')
        return if (since == null) "$base/$topic/json" else "$base/$topic/json?since=$since"
    }

    /** 解析一行 JSON 事件；非 message 事件（open/keepalive）返回 null。 */
    private fun parseLine(line: String): Message? {
        return try {
            val obj = JSONObject(line)
            if (obj.optString("event") != "message") return null
            Message(
                id = obj.optString("id"),
                title = obj.optString("title").ifBlank { null },
                body = obj.optString("message"),
                priority = obj.optInt("priority", 3),
                tags = obj.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).ifBlank { null } }
                } ?: emptyList(),
                click = obj.optString("click").ifBlank { null }
            )
        } catch (tr: Throwable) {
            Log.w(TAG, "bad json line: $line", tr)
            null
        }
    }

    /** 可被 [reconnectNow]/[stopSubscribe] 中断的退避睡眠。 */
    private fun sleep(ms: Long) {
        sleepThread = Thread.currentThread()
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            // 被唤醒：立即重试
        }
        sleepThread = null
    }
}
