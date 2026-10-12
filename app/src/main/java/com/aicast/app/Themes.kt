package com.aicast.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 待机首页主题系统。
 *
 * 主题分两类：
 * - native：内置 Compose 时钟（[ClockScreen]），零 WebView、省电，默认主题，id="native"。
 * - html：一整套 HTML 首页，渲染进 WebView（家模式，不计入投送内容），可用 SDK 交互。
 *   · builtin：随包 assets/themes/<id>.html；
 *   · agent：由 POST /theme 注册，存 filesDir/themes/<id>.html，重启保留。
 *
 * 当前选中项存 [Prefs.theme]。app 只负责装载主题，不预设视觉——把自定义空间留给 agent。
 */
object Themes {

    data class ThemeMeta(
        val id: String,
        val name: String,
        val kind: String,   // native | html
        val source: String  // builtin | agent
    )

    const val NATIVE = "native"

    // 内置 HTML 主题：id -> assets 路径。随包只读，不可被 agent 覆盖。
    // 显示名走字符串资源（[builtins]），否则切了语言主题名还停在原来的语言上。
    private val builtinAssetPath = mapOf(
        "aurora" to "themes/aurora.html",
        "minimal-light" to "themes/minimal-light.html",
    )

    private val themeDir get() = File(App.ctx.filesDir, "themes").apply { if (!exists()) mkdirs() }
    private val registryFile get() = File(themeDir, "registry.json")

    private var agentCache: List<ThemeMeta>? = null

    private fun loadAgent(): List<ThemeMeta> {
        agentCache?.let { return it }
        val list = try {
            if (registryFile.exists()) {
                val arr = JSONArray(registryFile.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ThemeMeta(
                        o.getString("id"),
                        o.optString("name", Str.get(R.string.theme_custom)),
                        "html",
                        "agent"
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        agentCache = list
        return list
    }

    /** 内置主题。每次现算：显示名要跟着当前语言，缓存会把切语言后的名字钉死 */
    private fun builtins(): List<ThemeMeta> = listOf(
        ThemeMeta(NATIVE, Str.get(R.string.theme_native), "native", "builtin"),
        ThemeMeta("aurora", Str.get(R.string.theme_aurora), "html", "builtin"),
        ThemeMeta("minimal-light", Str.get(R.string.theme_minimal_light), "html", "builtin"),
    )

    /** 全部可选主题：native + 内置 html + agent 注册（按注册顺序） */
    fun list(): List<ThemeMeta> = builtins() + loadAgent()

    fun exists(id: String): Boolean = list().any { it.id == id }

    /** 该主题是否需要用 WebView 渲染（html 家模式）；native 走 Compose */
    fun isHtml(id: String): Boolean = id != NATIVE && htmlFor(id) != null

    /** 取主题的 HTML 源码；native 或不存在返回 null */
    fun htmlFor(id: String): String? {
        if (id == NATIVE) return null
        builtinAssetPath[id]?.let { path ->
            return try {
                App.ctx.assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }
        return try {
            val f = File(themeDir, "$id.html")
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    fun nameOf(id: String): String = list().firstOrNull { it.id == id }?.name ?: id

    /**
     * 注册（或覆盖）一个 agent 主题。id 省略则自动生成。
     * 返回落盘的 ThemeMeta；html 为空抛异常由调用方处理。
     */
    fun register(html: String, name: String?, id: String?): ThemeMeta {
        require(html.isNotBlank()) { Str.get(R.string.theme_err_empty) }
        val tid = (id?.takeIf { it.matches(Regex("[A-Za-z0-9_\\-]{1,40}")) })
            ?: ("t_" + UUID.randomUUID().toString().take(8))
        val title = (name?.trim()?.takeIf { it.isNotEmpty() })
            ?: extractTitle(html)
        File(themeDir, "$tid.html").writeText(html)
        val meta = ThemeMeta(tid, title, "html", "agent")
        val cur = loadAgent().toMutableList()
        cur.removeAll { it.id == tid }
        cur.add(meta)
        persistAgent(cur)
        agentCache = cur
        return meta
    }

    fun remove(id: String): Boolean {
        if (id == NATIVE || builtinAssetPath.containsKey(id)) return false  // 内置不可删
        val cur = loadAgent().toMutableList()
        val changed = cur.removeAll { it.id == id }
        if (changed) {
            try { File(themeDir, "$id.html").delete() } catch (e: Exception) { /* ignore */ }
            persistAgent(cur)
            agentCache = cur
            // 删掉的正是在用主题时，要把界面拉回原生时钟（P0-7：否则一直停在已删主题上）
            if (Prefs.theme == id) {
                Prefs.theme = NATIVE
                HomeRefresh.invalidate()
            }
        }
        return changed
    }

    fun listJson(): String {
        val arr = JSONArray()
        list().forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("kind", it.kind).put("source", it.source)
            )
        }
        return JSONObject()
            .put("current", Prefs.theme)
            .put("themes", arr)
            .toString()
    }

    private fun persistAgent(list: List<ThemeMeta>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", "html"))
            }
            registryFile.writeText(arr.toString())
        } catch (e: Exception) { /* 写失败本次会话内存态仍在 */ }
    }

    private val titleRe = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val tagRe = Regex("(?s)<[^>]+>")

    private fun extractTitle(html: String): String {
        val text = titleRe.find(html)?.groupValues?.get(1)?.replace(tagRe, "")?.trim()
        return if (!text.isNullOrEmpty()) text.take(40) else Str.get(R.string.theme_custom_title)
    }
}
