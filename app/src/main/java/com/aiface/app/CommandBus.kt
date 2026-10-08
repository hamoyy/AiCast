package com.aiface.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * server ↔ WebView 的中央总线：渲染指令、事件环形缓冲、long-poll 拉取。
 * WebView 只允许在主线程操作，统一经 main 派发。
 */
object CommandBus {

    val json = Json { encodeDefaults = true }
    private val main = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var lastHtml: String? = null

    fun hasContent(): Boolean = currentUrl != null || lastHtml != null

    fun clear() {
        lastHtml = null; currentUrl = null
        // WebView 将被隐藏销毁内容，下次 render 重新载入
        main.post { App.webView?.loadUrl("about:blank") }
        onCleared?.invoke()
    }

    var onCleared: (() -> Unit)? = null

    fun renderHtml(html: String) {
        lastHtml = html; currentUrl = null
        val full = embedSdk(html)
        // baseURL 不能为 null：opaque origin 下页面访问 localStorage 会抛 SecurityError
        main.post { App.webView?.loadDataWithBaseURL("https://aiface.local/", full, "text/html", "UTF-8", null) }
    }

    /**
     * 家模式渲染：把一个 HTML 主题作为待机首页载入 WebView，但**不计入投送内容**
     * （hasContent 保持 false），这样返回键/清屏逻辑仍把它当待机，投送来时再覆盖。
     * 主题是整屏固定页：统一注入根元素禁滚动样式，防方向键/拖拽把页面推位
     * （个别老内核把 overflow 传播规则实现得不一致，双保险写在 html 与 body 上）。
     */
    fun showHome(html: String) {
        lastHtml = null; currentUrl = null
        val full = embedSdk(html) +
            "<style>html,body{overflow:hidden!important}</style>"
        main.post { App.webView?.loadDataWithBaseURL("https://aiface.local/", full, "text/html", "UTF-8", null) }
    }

    // 页面脚本在解析期就可能调用 Aiface，而 evaluateJavascript 要到 onPageFinished
    // 才执行——因此内联投送时必须把 SDK 先嵌进 HTML 头部。
    private var sdkCache: String? = null

    private fun sdkText(): String =
        sdkCache ?: run {
            val s = try {
                App.ctx.assets.open("aiface-sdk.js").bufferedReader().use { it.readText() }
            } catch (e: Exception) { "" }
            sdkCache = s
            s
        }

    private fun embedSdk(html: String): String {
        val sdk = sdkText()
        if (sdk.isEmpty()) return html
        val script = "<script>$sdk</script>"
        val m = Regex("(?i)<head[^>]*>").find(html)
        return if (m != null) {
            html.substring(0, m.range.last + 1) + script + html.substring(m.range.last + 1)
        } else {
            script + html
        }
    }

    fun loadUrl(url: String, wake: Boolean) {
        if (wake) main.post { App.activity?.let { Ui.wake(it) } }
        currentUrl = url; lastHtml = null
        main.post { App.webView?.loadUrl(url) }
    }

    /** 把数据经 JS SDK 注入当前页面（增量更新，不重载） */
    fun deliverMessage(data: String) {
        main.post {
            App.webView?.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('aiface-message',{detail:$data}))", null
            )
        }
    }

    fun eval(code: String, cb: (String?) -> Unit) {
        main.post { App.webView?.evaluateJavascript(code, cb) }
    }

    fun stateJson() = buildJsonObject {
        put("hasContent", JsonPrimitive(hasContent()))
        put("url", JsonPrimitive(currentUrl ?: (if (lastHtml != null) "(inline-html)" else "")))
        put("screenOn", JsonPrimitive(Ui.isScreenOn()))
        put("brightness", JsonPrimitive(Params.get("brightness")))
        put("theme", JsonPrimitive(Prefs.theme))
        put("fontScale", JsonPrimitive(Params.get("fontScale")))
        put("activityAlive", JsonPrimitive(App.activity != null))
        put("paramDbg", JsonPrimitive(Params.lastDbg))
    }

    // ---------- 事件环形缓冲（agent 单向 long-poll 拉取） ----------
    data class Ev(val seq: Long, val at: Long, val kind: String, val data: String)

    private val events = kotlin.collections.ArrayDeque<Ev>()
    private var seq = 0L

    fun emit(kind: String, data: String) {
        synchronized(events) {
            events.addLast(Ev(++seq, System.currentTimeMillis(), kind, data))
            while (events.size > 200) events.removeFirst()
        }
    }

    /** since 之后的事件；JSON 数组字符串。data 字段由调用方保证为合法 JSON */
    fun eventsSince(since: Long): String = synchronized(events) {
        events.filter { it.seq > since }.joinToString(",", "[", "]") { e ->
            """{"seq":${e.seq},"at":${e.at},"kind":"${e.kind}","data":${e.data}}"""
        }
    }

    fun lastSeq(): Long = synchronized(events) { events.lastOrNull()?.seq ?: 0L }
}

object Ui {
    /** 点亮/保持屏幕（M1 简化版：唤醒标志 + 常亮；完整 screen 控制见 M8） */
    @Suppress("DEPRECATION")
    fun wake(a: Activity) {
        a.window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
    }

    fun isScreenOn(): Boolean = try {
        val pm = App.ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    } catch (e: Exception) { true }
}

/**
 * 主题/家模式刷新桥：server 线程或参数变更后，请求 [MainActivity] 在 UI 线程重画首页。
 * activity 未起时静默跳过——起来时 onCreate 会按当前 Prefs.theme 自行渲染。
 */
object HomeRefresh {
    @Volatile var request: (() -> Unit)? = null
    fun invalidate() {
        val act = App.activity ?: return
        act.runOnUiThread { request?.invoke() }
    }
}

/** app 参数控制（§6.1/§7 子集：M1 先做亮度/主题/字号/音量） */
object Params {
    private val sp get() = App.ctx.getSharedPreferences("aiface_params", Context.MODE_PRIVATE)
    @Volatile var lastDbg = ""
        private set

    fun get(key: String): String = when (key) {
        "brightness" -> sp.getString("brightness", "auto") ?: "auto"
        "theme" -> Prefs.theme
        "fontScale" -> sp.getString("fontScale", "1.0") ?: "1.0"
        "volume" -> sp.getString("volume", "") ?: ""
        else -> sp.getString(key, "") ?: ""
    }

    fun set(key: String, value: String): Boolean = when (key) {
        "brightness" -> {
            sp.edit().putString(key, value).apply()
            val a = App.activity
            val f = value.toFloatOrNull()
            lastDbg = "brightness=$value activity=${a != null}"
            if (a != null) {
                a.runOnUiThread {
                    // 必须传入新对象：setAttributes 对同引用会直接跳过
                    val lp = WindowManager.LayoutParams()
                    lp.copyFrom(a.window.attributes)
                    lp.screenBrightness = f?.coerceIn(0.01f, 1f)
                        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    a.window.attributes = lp
                    lastDbg += " applied; screenBrightness=${a.window.attributes.screenBrightness}"
                }
            }
            true
        }
        "fontScale", "rotationLock", "idleDimSeconds" -> {
            sp.edit().putString(key, value).apply()
            true
        }
        "theme" -> {
            if (Themes.exists(value)) {
                Prefs.theme = value
                HomeRefresh.invalidate()
                true
            } else false
        }
        "volume" -> {
            val v = value.toFloatOrNull() ?: return false
            val am = App.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (v.coerceIn(0f, 1f) * max).toInt(), 0
            )
            sp.edit().putString(key, value).apply()
            true
        }
        else -> false
    }
}
