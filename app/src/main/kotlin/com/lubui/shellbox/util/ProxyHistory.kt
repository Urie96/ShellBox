package com.lubui.shellbox.util

import android.content.Context

/**
 * 最近使用过的代理地址历史记录。
 *
 * - 以换行符分隔存于 SharedPreferences（代理地址形如 host:port，不含换行）。
 * - 最新使用的排在最前，最多保留 [MAX_ENTRIES] 条。
 * - 兼容旧版本：旧版本只存了最后一个地址（key = "http_proxy"），首次读取时迁移。
 */
object ProxyHistory {

    const val PREFS = "proxy"

    private const val KEY_HISTORY = "proxy_history"
    private const val KEY_LEGACY = "http_proxy" // 旧版本只存最后一个地址
    private const val MAX_ENTRIES = 10

    /** 最近使用的代理地址列表，按最近使用排序（最新的在前）。 */
    fun getHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var raw = prefs.getString(KEY_HISTORY, null)
        if (raw == null) {
            // 旧版本迁移：把旧 key 里存的最后一个地址作为历史
            val legacy = prefs.getString(KEY_LEGACY, null)
            if (legacy.isNullOrBlank()) return emptyList()
            raw = legacy
            prefs.edit().putString(KEY_HISTORY, legacy).apply()
        }
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 最近一次使用的代理地址；从未设置过时返回 null。 */
    fun getLast(context: Context): String? = getHistory(context).firstOrNull()

    /** 记录一次使用的代理地址：去重并移到最前，超限时丢弃最旧的。 */
    fun add(context: Context, proxy: String) {
        val clean = proxy.trim()
        if (clean.isEmpty()) return
        val history = getHistory(context).filter { it != clean }.toMutableList()
        history.add(0, clean)
        val trimmed = history.take(MAX_ENTRIES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, trimmed.joinToString("\n"))
            .putString(KEY_LEGACY, clean)
            .apply()
    }
}
