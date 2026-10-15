package com.aicast.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * server ↔ WebView 的中央总线：渲染指令、事件环形缓冲、long-poll 拉取。
 * WebView 只允许在主线程操作，统一经 main 派发。
 */
object CommandBus {

    val json = Json { encodeDefaults = true }
    private val main = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var lastHtml: String? = null

    /** 界面还没起来时的投送排队目标（P0-4：避免 /render 假成功） */
    @Volatile var pendingHtml: String? = null
        private set

    fun hasContent(): Boolean = currentUrl != null || lastHtml != null
    fun hasPending(): Boolean = pendingHtml != null

    fun clear() {
        lastHtml = null; currentUrl = null; pendingHtml = null
        // WebView 将被隐藏销毁内容，下次 render 重新载入
        main.post { App.webView?.loadUrl("about:blank") }
        onCleared?.invoke()
    }

    var onCleared: (() -> Unit)? = null

    /**
     * 投送 HTML。返回是否上了屏：
     * - true：已投给 WebView；
     * - false：当前没有 Activity/WebView，内容进 [pendingHtml] 排队，
     *   等界面起来由 [flushPending] 补投（调用方据此回 `queued`，别再假装成功）。
     */
    fun renderHtml(html: String): Boolean {
        lastHtml = html; currentUrl = null
        if (App.webView == null) {
            pendingHtml = html // 只保留最后一份，避免堆积内存
            return false
        }
        pendingHtml = null
        val full = embedSdk(html)
        // baseURL 不能为 null：opaque origin 下页面访问 localStorage 会抛 SecurityError
        main.post { App.webView?.loadDataWithBaseURL("https://aicast.local/", full, "text/html", "UTF-8", null) }
        return true
    }

    /** 界面就绪后把排队的内容补投出去（幂等） */
    fun flushPending() {
        val html = pendingHtml ?: return
        main.post {
            val wv = App.webView ?: return@post
            if (pendingHtml == null) return@post
            pendingHtml = null
            lastHtml = html; currentUrl = null
            wv.loadDataWithBaseURL("https://aicast.local/", embedSdk(html), "text/html", "UTF-8", null)
        }
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
        main.post { App.webView?.loadDataWithBaseURL("https://aicast.local/", full, "text/html", "UTF-8", null) }
    }

    // 页面脚本在解析期就可能调用 AiCast，而 evaluateJavascript 要到 onPageFinished
    // 才执行——因此内联投送时必须把 SDK 先嵌进 HTML 头部。
    private var sdkCache: String? = null

    private fun sdkText(): String =
        sdkCache ?: run {
            val s = try {
                App.ctx.assets.open("aicast-sdk.js").bufferedReader().use { it.readText() }
            } catch (e: Exception) { "" }
            sdkCache = s
            s
        }

    /**
     * 语言标记：内置主题（assets 下的 HTML 主题文件）是静态资源，走不了 Android 资源解析，
     * 只能由宿主把当前语言塞进页面，页面据它挑星期名/日期格式。
     * 取值来自 `lang_code` 字符串资源，因此与实际命中的那套文案永远一致。
     */
    private fun langScript(): String {
        val lang = Str.lang.filter { it.isLetter() }.take(8).ifEmpty { "zh" }
        return """<script>window.__AICAST_LANG__="$lang";</script>"""
    }

    private fun embedSdk(html: String): String {
        val sdk = sdkText()
        val script = langScript() + if (sdk.isEmpty()) "" else "<script>$sdk</script>"
        val m = Regex("(?i)<head[^>]*>").find(html)
        return if (m != null) {
            html.substring(0, m.range.last + 1) + script + html.substring(m.range.last + 1)
        } else {
            script + html
        }
    }

    /** 无可用 WebView 返回 false（调用方应回明确错误，不要让 agent 以为生效了） */
    fun loadUrl(url: String, wake: Boolean): Boolean {
        if (App.webView == null) return false
        if (wake) main.post { App.activity?.let { Ui.wake(it) } }
        currentUrl = url; lastHtml = null; pendingHtml = null
        main.post { App.webView?.loadUrl(url) }
        return true
    }

    /**
     * 把数据经 JS SDK 注入当前页面（增量更新，不重载）。
     * 入参必须是合法 JSON，解析后重新序列化再注入——避免把任意脚本拼进 evaluateJavascript。
     * 当前没有页面则返回 false。
     */
    fun deliverMessage(data: String): Boolean {
        val el = try { Json.parseToJsonElement(data) } catch (e: Exception) { return false }
        if (App.webView == null) return false
        val payload = el.toString()
        main.post {
            App.webView?.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('aicast-message',{detail:$payload}))", null
            )
        }
        return true
    }

    fun stateJson() = buildJsonObject {
        put("hasContent", JsonPrimitive(hasContent()))
        put("url", JsonPrimitive(currentUrl ?: (if (lastHtml != null) "(inline-html)" else "")))
        put("pending", JsonPrimitive(pendingHtml != null))
        put("screenOn", JsonPrimitive(Ui.isScreenOn()))
        put("brightness", JsonPrimitive(Params.get("brightness")))
        put("theme", JsonPrimitive(Prefs.theme))
        put("fontScale", JsonPrimitive(Params.get("fontScale")))
        put("activityAlive", JsonPrimitive(App.activity != null))
        put("lastSeq", JsonPrimitive(lastSeq()))
        put("paramDbg", JsonPrimitive(Params.lastDbg))
    }

    // ---------- 事件环形缓冲（agent 单向 long-poll 拉取） ----------
    data class Ev(val seq: Long, val at: Long, val kind: String, val data: String)

    private val lock = Any()
    private val events = kotlin.collections.ArrayDeque<Ev>()

    /** seq 落盘：进程重启也不回退，agent 的 since 不会被"回到 0"卡死（P0-2） */
    private fun seqSp() = App.ctx.getSharedPreferences("aicast_events", Context.MODE_PRIVATE)

    private fun nextSeq(): Long {
        val sp = seqSp()
        val s = sp.getLong("seq", 0L) + 1
        sp.edit().putLong("seq", s).apply()
        return s
    }

    /** app 内部事件（可信来源）：system.* / auth.* / media.* / render.* … */
    fun emit(kind: String, data: String) {
        val e = Ev(nextSeq(), System.currentTimeMillis(), kind, data)
        synchronized(lock) {
            events.addLast(e)
            while (events.size > 200) events.removeFirst()
        }
        wakeWaiters()
    }

    // 页面冒名用的命名空间：一律降级到 page.* 前缀
    private val RESERVED_PREFIXES = listOf(
        "system.", "auth.", "app.", "device.", "capability.", "render.", "media.", "theme."
    )

    /**
     * 页面事件（不可信来源）：限字符集与长度，并阻断冒名系统命名空间（P0-5）。
     * `slide.played` → `slide.played`（兼容现有页面）；`system.page` → `page.system.page`。
     */
    fun emitFromPage(rawType: String, data: String) {
        var s = rawType
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .take(64).trim('.', '_', '-')
        if (s.isEmpty()) return
        if (RESERVED_PREFIXES.any { s.startsWith(it, ignoreCase = true) }) s = "page.$s"
        emit(if (s.contains('.')) s else "ui.$s", data)
    }

    /** data 必须是合法 JSON；不是就整体当字符串，绝不破坏响应结构 */
    private fun safeData(data: String): JsonElement = try {
        Json.parseToJsonElement(data)
    } catch (e: Exception) {
        JsonPrimitive(data.take(2000))
    }

    private fun evJson(e: Ev): JsonObject = JsonObject(
        mapOf(
            "seq" to JsonPrimitive(e.seq),
            "at" to JsonPrimitive(e.at),
            "kind" to JsonPrimitive(e.kind),
            "data" to safeData(e.data)
        )
    )

    fun eventsSince(since: Long): List<Ev> = synchronized(lock) { events.filter { it.seq > since } }

    /** 序列化生成，kind/data 均经过转义——不再手写字符串拼接（P0-5） */
    fun eventsJson(since: Long): JsonArray = JsonArray(eventsSince(since).map { evJson(it) })

    fun lastSeq(): Long = seqSp().getLong("seq", 0L)

    // long-poll 唤醒器：有事件立刻返回，不再 500ms 轮询
    private val signal = java.util.concurrent.atomic.AtomicInteger(0)
    private val awaitLock = java.util.concurrent.locks.ReentrantLock()
    private val awaitCond = awaitLock.newCondition()

    private fun wakeWaiters() {
        signal.incrementAndGet()
        awaitLock.lock()
        try { awaitCond.signalAll() } finally { awaitLock.unlock() }
    }

    /** 最长等 timeoutMs；true = 有新事件被唤醒，false = 超时 */
    fun awaitChange(timeoutMs: Long): Boolean {
        val token = signal.get()
        val deadline = System.currentTimeMillis() + timeoutMs
        awaitLock.lock()
        try {
            while (System.currentTimeMillis() < deadline) {
                if (signal.get() != token) return true
                runCatching { awaitCond.await(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
            return signal.get() != token
        } finally { awaitLock.unlock() }
    }
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

    /** 冷启动/重建后回放持久化的亮度，避免界面与 /param 报告不一致 */
    fun applyPersistedBrightness(a: Activity) {
        val v = App.ctx.getSharedPreferences("aicast_params", Context.MODE_PRIVATE)
            .getString("brightness", "auto") ?: "auto"
        setWindowBrightness(a, v.toFloatOrNull())
    }

    @Suppress("DEPRECATION")
    fun setWindowBrightness(a: Activity, f: Float?) {
        val lp = WindowManager.LayoutParams()
        lp.copyFrom(a.window.attributes)
        lp.screenBrightness = f?.coerceIn(0.01f, 1f)
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        a.window.attributes = lp
    }

    fun isScreenOn(): Boolean = try {
        val pm = App.ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    } catch (e: Exception) { true }

    /**
     * 保持屏幕常亮的开关。
     *
     * 夜间熄屏时段会把它摘掉——这是普通 app 唯一合法的"关屏"手段：不申请任何特殊权限，
     * 松开常亮后交给系统自己的「屏幕超时」去熄屏（真正熄屏、背光也灭、省电）。
     * 想立刻变暗可以配合 [setWindowBrightness] 传一个极小值。
     */
    @Suppress("DEPRECATION")
    fun setKeepAwake(a: Activity, on: Boolean) {
        if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
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

/** app 参数控制（§6.1/§7 子集：亮度/主题/字号/音量） */
object Params {
    private val sp get() = App.ctx.getSharedPreferences("aicast_params", Context.MODE_PRIVATE)
    @Volatile var lastDbg = ""
        private set

    fun get(key: String): String = when (key) {
        "brightness" -> sp.getString("brightness", "auto") ?: "auto"
        "theme" -> Prefs.theme
        "fontScale" -> sp.getString("fontScale", "1.0") ?: "1.0"
        "volume" -> sp.getString("volume", "") ?: ""
        else -> sp.getString(key, "") ?: ""
    }

    fun supportedKeys() = listOf("brightness", "theme", "fontScale", "volume")

    fun set(key: String, value: String): Boolean = when (key) {
        "brightness" -> {
            sp.edit().putString(key, value).apply()
            val a = App.activity
            val f = value.toFloatOrNull()
            lastDbg = "brightness=$value activity=${a != null}"
            if (a != null) {
                a.runOnUiThread {
                    // 必须传入新对象：setAttributes 对同引用会直接跳过
                    Ui.setWindowBrightness(a, f)
                    lastDbg += " applied; screenBrightness=${a.window.attributes.screenBrightness}"
                }
            }
            true
        }
        "fontScale" -> {
            val f = value.toFloatOrNull()
            if (f == null || f < 0.5f || f > 3f) false
            else { sp.edit().putString(key, value).apply(); true }
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
        // rotationLock / idleDimSeconds 暂未实现：不再假装成功（P1-11）
        else -> false
    }
}
