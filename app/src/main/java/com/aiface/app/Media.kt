package com.aiface.app

import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 媒体存储（§5 /media）：agent 上传音频/图片等资源，设备本地落盘，页面经
 * `http://<设备IP>:43210/media/<id>` 用 Range 流式拉取——用于设备直连外网
 * 太慢/不通时的局域网兜底（如低配电视播整轨音乐）。
 *
 * 低配电视存储有限，所以：
 * - 配额 [QUOTA_BYTES] 环形淘汰（超了丢最旧），加 [TTL_MS] 过期自动清；
 * - 上传前用 StatFs 检查剩余空间，不足直接拒绝（507），并始终给系统盘留底；
 * - 大文件流式写盘（Server 先写临时文件，再 [commit] 入索引），不进内存。
 *
 * 索引存 filesDir/media/index.json，重启保留。app 只存不解析内容。
 */
object Media {

    data class Meta(
        val id: String,
        val name: String,
        val type: String,   // mime
        val size: Long,
        val ts: Long
    )

    private const val QUOTA_BYTES = 32L * 1024 * 1024      // 环形上限 32MB
    private const val TTL_MS = 72L * 60 * 60 * 1000        // 72 小时过期
    const val MAX_SINGLE_BYTES = 64L * 1024 * 1024         // 单文件硬上限 64MB
    private const val MIN_FREE = 64L * 1024 * 1024         // 存完后系统盘至少留 64MB

    private val dir get() = File(App.ctx.filesDir, "media").apply { if (!exists()) mkdirs() }
    private val indexFile get() = File(dir, "index.json")
    private val extRe = Regex("\\.([A-Za-z0-9]{1,8})$")
    private val safeId = Regex("[A-Za-z0-9_.\\-]{1,48}")

    private var cache: MutableList<Meta>? = null

    private fun extOf(name: String): String =
        extRe.find(name)?.groupValues?.get(1)?.lowercase() ?: "bin"

    private fun fileFor(meta: Meta): File = File(dir, meta.id)

    private fun load(): MutableList<Meta> {
        cache?.let { return it }
        val list = try {
            if (indexFile.exists()) {
                val arr = JSONArray(indexFile.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Meta(o.getString("id"), o.optString("name", ""),
                        o.optString("type", "application/octet-stream"),
                        o.optLong("size"), o.optLong("ts"))
                }.toMutableList()
            } else mutableListOf()
        } catch (e: Exception) { mutableListOf() }
        cache = list
        return list
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            load().forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name)
                    .put("type", it.type).put("size", it.size).put("ts", it.ts))
            }
            indexFile.writeText(arr.toString())
        } catch (e: Exception) { /* 写失败本次内存态仍在 */ }
    }

    /** 剩余空间是否够存 [need] 字节（并给系统盘留底） */
    fun hasRoom(need: Long): Boolean = try {
        val st = StatFs(App.ctx.filesDir.absolutePath)
        st.availableBytes - need >= MIN_FREE
    } catch (e: Exception) { true }

    fun get(id: String): Meta? {
        if (!id.matches(safeId)) return null
        return load().firstOrNull { it.id == id }
    }

    fun fileOf(meta: Meta): File? = fileFor(meta).takeIf { it.exists() }

    fun list(): List<Meta> = load().toList()

    /**
     * 把已写好的临时文件收编为正式媒体项。空间不足/超限抛 IllegalStateException，
     * 调用方负责回 507/413；本函数总会在异常时删掉临时文件。
     */
    fun commit(name: String, type: String, tmp: File): Meta {
        val size = tmp.length()
        if (size <= 0) { tmp.delete(); throw IllegalArgumentException("空内容") }
        if (size > MAX_SINGLE_BYTES) { tmp.delete(); throw IllegalStateException("单文件超过 ${MAX_SINGLE_BYTES / 1024 / 1024}MB 上限") }
        if (!hasRoom(size)) { tmp.delete(); throw IllegalStateException("设备存储不足") }
        val meta = Meta(
            id = "m_" + UUID.randomUUID().toString().take(8) + "." + extOf(name),
            name = name.trim().take(80).ifEmpty { "media" },
            type = type.ifBlank { "application/octet-stream" },
            size = size,
            ts = System.currentTimeMillis()
        )
        if (!tmp.renameTo(fileFor(meta))) {
            tmp.copyTo(fileFor(meta), overwrite = true)
            tmp.delete()
        }
        val cur = load()
        cur.add(0, meta) // 头 = 最新
        enforceQuota(cur)
        cache = cur
        persist()
        return meta
    }

    fun remove(id: String): Boolean {
        val cur = load()
        val m = cur.firstOrNull { it.id == id } ?: return false
        try { fileFor(m).delete() } catch (e: Exception) { /* ignore */ }
        cur.removeAll { it.id == id }
        cache = cur
        persist()
        return true
    }

    /** TTL 过期 + 配额环形淘汰（丢最旧） */
    private fun enforceQuota(cur: MutableList<Meta>) {
        val now = System.currentTimeMillis()
        cur.filter { now - it.ts > TTL_MS }.forEach { try { fileFor(it).delete() } catch (e: Exception) {} }
        cur.removeAll { now - it.ts > TTL_MS }
        var total = cur.sumOf { it.size }
        while (total > QUOTA_BYTES && cur.isNotEmpty()) {
            val oldest = cur.removeAt(cur.size - 1)
            total -= oldest.size
            try { fileFor(oldest).delete() } catch (e: Exception) {}
        }
    }

    fun listJson(): String {
        val arr = JSONArray()
        load().forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("type", it.type).put("size", it.size).put("ts", it.ts))
        }
        return JSONObject()
            .put("quota", QUOTA_BYTES)
            .put("used", load().sumOf { it.size })
            .put("media", arr)
            .toString()
    }
}
