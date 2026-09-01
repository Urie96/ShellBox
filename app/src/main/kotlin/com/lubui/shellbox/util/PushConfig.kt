package com.lubui.shellbox.util

import android.content.Context

/**
 * 服务端推送（ntfy）配置持久化：服务器地址 / topic / 访问令牌。
 *
 * - token 可选但强烈建议（t_ 开头的访问令牌，经 `Authorization: Bearer` 传输）；
 * - last_id 内部使用：断线重连时用 `since=<lastId>` 补收错过的消息（ntfy 默认缓存 12h）；
 * - 修改 server/topic 后必须调用 [clearLastId]，避免把旧 topic 的消息 id 传给新 topic。
 */
object PushConfig {

    const val PREFS = "push"

    private const val KEY_SERVER = "server"
    private const val KEY_TOPIC = "topic"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_ID = "last_id"
    private const val KEY_AUTO_START = "auto_start"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ntfy 服务器地址，如 `https://push.example.com`（不含末尾斜杠，不含 topic）。 */
    fun getServer(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()

    /** topic（随机串，相当于「密码」；NAS 发推送时拼在服务器地址后面）。 */
    fun getTopic(context: Context): String = prefs(context).getString(KEY_TOPIC, "").orEmpty()

    /** 访问令牌（t_ 开头，可选）。 */
    fun getToken(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    /** 最近一条已展示消息的 id；从未收到过消息时为 null。 */
    fun getLastId(context: Context): String? = prefs(context).getString(KEY_LAST_ID, null)

    fun setServer(context: Context, server: String) {
        prefs(context).edit().putString(KEY_SERVER, server.trim()).apply()
    }

    fun setTopic(context: Context, topic: String) {
        prefs(context).edit().putString(KEY_TOPIC, topic.trim()).apply()
    }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /**
     * 持久化最后收到的消息 id。用同步 [android.content.SharedPreferences.Editor.commit]：
     * 订阅线程上调用，宁可短暂阻塞也不能丢（丢了重连会重发旧消息）。
     */
    fun setLastId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_ID, id).commit()
    }

    /** server/topic 变更后调用：丢弃旧 id，下次重连只收新消息。 */
    fun clearLastId(context: Context) {
        prefs(context).edit().remove(KEY_LAST_ID).commit()
    }

    /** 是否开机自动启动推送服务（默认关，需在应用内手动打开）。 */
    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    /** server 和 topic 都已填写（基本配置完备）。 */
    fun isConfigured(context: Context): Boolean =
        getServer(context).isNotBlank() && getTopic(context).isNotBlank()
}
