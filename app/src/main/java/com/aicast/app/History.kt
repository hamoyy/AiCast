package com.aicast.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 投送历史：记录最近 N 条 agent 投送的内容，误返回后可从首页唤出重播。
 * 落盘 filesDir/history.json，重启不丢。单条 HTML 超过 [MAX_FULL] 只存摘要（无法完整还原）。
 */
object History {

    data class Entry(
        val id: String,
        val ts: Long,
        val title: String,
        val via: String,
        val size: Int,
        val truncated: Boolean,
        val html: String?  // truncated 时为预览片段，否则为完整 HTML
    )

    private const val MAX = 10
    private const val MAX_FULL = 512 * 1024  // 512KB 以上不存全文，防吃满存储

    private val file get() = File(App.ctx.filesDir, "history.json")
    private val items = ArrayDeque<Entry>()  // 头 = 最新
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        items.addLast(
                            Entry(
                                id = o.getString("id"),
                                ts = o.getLong("ts"),
                                title = o.getString("title"),
                                via = o.optString("via"),
                                size = o.optInt("size"),
                                truncated = o.optBoolean("truncated"),
                                html = if (o.isNull("html")) null else o.optString("html")
                            )
                        )
                    }
                }
            } catch (e: Exception) { /* 损坏则从空开始 */ }
            loaded = true
        }
    }

    fun add(html: String, via: String) {
        ensure()
        val truncated = html.length > MAX_FULL
        val e = Entry(
            id = "h_" + UUID.randomUUID().toString().take(8),
            ts = System.currentTimeMillis(),
            title = extractTitle(html),
            via = via,
            size = html.length,
            truncated = truncated,
            html = if (truncated) html.take(2000) else html
        )
        synchronized(this) {
            items.addFirst(e)
            while (items.size > MAX) items.removeLast()
            persist()
        }
    }

    fun list(): List<Entry> { ensure(); return synchronized(this) { items.toList() } }

    fun get(id: String): Entry? = list().firstOrNull { it.id == id }

    fun clear() {
        synchronized(this) { items.clear(); persist() }
    }

    /** 仅元数据（不含 html），供 GET /history 与列表 UI 用 */
    fun listJson(): String {
        val arr = JSONArray()
        list().forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("ts", it.ts).put("title", it.title)
                    .put("via", it.via).put("size", it.size).put("truncated", it.truncated)
            )
        }
        return arr.toString()
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("ts", it.ts).put("title", it.title)
                        .put("via", it.via).put("size", it.size).put("truncated", it.truncated)
                        .put("html", it.html ?: JSONObject.NULL)
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) { /* 写失败不影响本次会话内存态 */ }
    }

    private val titleRe = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val h1Re = Regex("(?is)<h1[^>]*>(.*?)</h1>")
    private val tagRe = Regex("(?s)<[^>]+>")

    /**
     * 现用现建：语言切了以后进程未必重启（Activity 重建而已），
     * 缓存成 val 的话日期会一直停在旧语言的 Locale 上。
     */
    fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun extractTitle(html: String): String {
        val raw = titleRe.find(html)?.groupValues?.get(1)
            ?: h1Re.find(html)?.groupValues?.get(1)
        val text = raw?.replace(tagRe, "")?.trim()
        return if (!text.isNullOrEmpty()) text.take(40) else Str.get(R.string.history_untitled)
    }
}
