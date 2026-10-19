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
        val html: String?,  // truncated 时为预览片段，否则为完整 HTML
        val url: String? = null  // url 投送的地址；与 html 二选一（url 模式不缓存页面内容）
    )

    private const val MAX = 10
    private const val MAX_FULL = 2 * 1024 * 1024  // 2MB 以上不存全文，防吃满存储
                                                  // （服务端单次接收上限是 4MB，见 Server.MAX_BODY_BYTES）

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
                                html = if (o.isNull("html")) null else o.optString("html"),
                                url = o.optString("url", "").ifEmpty { null }
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

    /**
     * URL 投送：只记地址，不缓存页面内容（第三方资源随时会变，缓存一份反而是假象）。
     * 以前 url 投送一条历史都不留，用户说「没看到」时既不能重播也查不到投过什么。
     */
    fun addUrl(url: String, via: String) {
        ensure()
        val e = Entry(
            id = "h_" + UUID.randomUUID().toString().take(8),
            ts = System.currentTimeMillis(),
            title = prettyUrl(url),
            via = via,
            size = 0,
            truncated = false,
            html = null,
            url = url
        )
        synchronized(this) {
            items.addFirst(e)
            while (items.size > MAX) items.removeLast()
            persist()
        }
    }

    /** 列表标题：去掉协议和末尾斜杠，太长的截断 */
    private fun prettyUrl(url: String): String =
        url.substringAfter("://", url).trimEnd('/').take(40).ifEmpty { Str.get(R.string.history_untitled) }

    fun clear() {
        synchronized(this) { items.clear(); persist() }
    }

    /** 单向删除单条记录（id 不存在时静默忽略） */
    fun remove(id: String) {
        synchronized(this) {
            if (items.removeIf { it.id == id }) persist()
        }
    }

    /** 仅元数据（不含 html），供 GET /history 与列表 UI 用 */
    fun listJson(): String {
        val arr = JSONArray()
        list().forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("ts", it.ts).put("title", it.title)
                    .put("via", it.via).put("size", it.size).put("truncated", it.truncated)
                    .put("url", it.url ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    /**
     * 落盘线程。
     *
     * persist() 是在 /render 的请求线程上调用的，而这里是**全量重写**整个文件；
     * 单条上限 2MB × 10 条意味着最坏情况要写 ~20MB，同步写会明显拖住响应
     * （docs/REVIEW-2026-10-08.md 第 18 条早就点出过，当时上限还是 512KB）。
     *
     * 所以：内存态改动仍然同步生效（UI 立刻看到），只有磁盘写入挪到这里。
     * 单线程保证落盘顺序与调用顺序一致；进程若在写入完成前被杀，丢的是最后
     * 一次变更——历史数据，可以接受。
     */
    private val writer: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "aicast-history-writer").apply { isDaemon = true }
        }

    /** 序列化当前内存态（**调用方必须已持有本对象的锁**） */
    private fun snapshot(): String {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("ts", it.ts).put("title", it.title)
                    .put("via", it.via).put("size", it.size).put("truncated", it.truncated)
                    .put("html", it.html ?: JSONObject.NULL)
                    .put("url", it.url ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    /** 排一次落盘（**调用方必须已持有本对象的锁**）：锁内只做序列化，写盘交给 [writer] */
    private fun persist() {
        val json = try { snapshot() } catch (e: Exception) { return }
        writer.execute {
            try { file.writeText(json) } catch (e: Exception) { /* 写失败不影响本次会话内存态 */ }
        }
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
