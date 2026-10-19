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

    /** 排队内容是否要求亮屏投（`--wake` 对 HTML 投送同样生效） */
    @Volatile private var pendingWake = false

    /**
     * 内容已下发给 WebView、`onPageFinished` 还没到。
     *
     * 这段窗口里主题刷新**绝不能插队**：`syncLayers()` 看到 `hasContent` 还是 false，
     * 就会走 `showHomeTheme()` 把待机主题 load 到内容之后，屏幕上只剩主题。
     * 见 `MainActivity.syncLayers()` 的同名判断。
     */
    @Volatile private var contentRendering = false

    fun hasContent(): Boolean = currentUrl != null || lastHtml != null
    fun hasPending(): Boolean = pendingHtml != null
    fun isContentRendering(): Boolean = contentRendering

    /** 内容页加载完成（由 MainActivity.onPageFinished 按页面自报身份回填） */
    fun markContentLoaded() { contentRendering = false }

    /** 主框架加载失败：不再等 onPageFinished，立刻允许主题层接管 */
    fun markLoadFailed() { contentRendering = false }

    fun clear() {
        lastHtml = null; currentUrl = null; pendingHtml = null
        pendingWake = false; contentRendering = false
        // WebView 将被隐藏销毁内容，下次 render 重新载入
        main.post { App.webView?.loadUrl("about:blank") }
        onCleared?.invoke()
    }

    var onCleared: (() -> Unit)? = null

    /**
     * 投送 HTML。返回是否**真的呈现到了屏幕上**：
     * - true：已交给可见的 WebView；
     * - false：界面不存在或不在前台，内容进 [pendingHtml] 排队，
     *   等界面回到前台由 [flushPending] 补投（调用方据此回 `queued`，别再假装成功）。
     */
    fun renderHtml(html: String, wake: Boolean = false): Boolean {
        currentUrl = null
        if (App.webView == null || !App.foreground) {
            pendingHtml = html // 只保留最后一份，避免堆积内存
            pendingWake = pendingWake || wake
            lastHtml = null    // 排队中不算"已上屏"：hasContent 保持 false，状态看 pending
            contentRendering = false
            return false
        }
        pendingHtml = null
        pendingWake = false
        lastHtml = html
        contentRendering = true
        val full = embedSdk(html, KIND_CONTENT)
        // baseURL 不能为 null：opaque origin 下页面访问 localStorage 会抛 SecurityError
        main.post {
            if (wake) App.activity?.let { Ui.wake(it) }
            App.webView?.loadDataWithBaseURL("https://aicast.local/", full, "text/html", "UTF-8", null)
        }
        return true
    }

    /** 界面就绪后把排队的内容补投出去（幂等） */
    fun flushPending() {
        val html = pendingHtml ?: return
        main.post {
            val wv = App.webView ?: return@post
            if (pendingHtml != html) return@post   // 期间已被更新的投送覆盖
            val wake = pendingWake
            pendingHtml = null; pendingWake = false
            lastHtml = html; currentUrl = null; contentRendering = true
            if (wake) App.activity?.let { Ui.wake(it) }
            wv.loadDataWithBaseURL("https://aicast.local/", embedSdk(html, KIND_CONTENT), "text/html", "UTF-8", null)
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
        // 注意：**不要清 pendingHtml**。排队中的投送代表"用户回到前台就该看到的那一屏"，
        // 主题刷新不该把它丢掉（回到前台时 flushPending 会盖在主题上）。
        contentRendering = false
        val full = embedSdk(html, KIND_THEME) +
            "<style>html,body{overflow:hidden!important}</style>"
        main.post { App.webView?.loadDataWithBaseURL("https://aicast.local/", full, "text/html", "UTF-8", null) }
    }

    /**
     * 页面加载完成后自报的身份标记。
     *
     * 以前 `onPageFinished` 用全局 `hasContent()` 判断"这是不是内容页"——主题页晚于内容页
     * 完成时会把主题当成内容：`/state` 报 hasContent=true，屏幕上是时钟。
     * 现在每个页面自己带着标记回来（内容="content"、主题="theme"），与完成顺序无关；
     * url 投送的第三方页面没有标记，按内容处理。
     */
    const val KIND_CONTENT = "content"
    const val KIND_THEME = "theme"

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

    private fun embedSdk(html: String, kind: String): String {
        val sdk = sdkText()
        // kind 标记要放在 SDK 之前：SDK 与页面都可能在解析期就读它
        val script = langScript() +
            "<script>window.__AICAST_KIND=\"$kind\";</script>" +
            if (sdk.isEmpty()) "" else "<script>$sdk</script>"
        val m = Regex("(?i)<head[^>]*>").find(html)
        return if (m != null) {
            html.substring(0, m.range.last + 1) + script + html.substring(m.range.last + 1)
        } else {
            script + html
        }
    }

    /** 无可用（可见）WebView 返回 false（调用方应回明确错误，不要让 agent 以为生效了） */
    fun loadUrl(url: String, wake: Boolean): Boolean {
        if (App.webView == null || !App.foreground) return false
        if (wake) main.post { App.activity?.let { Ui.wake(it) } }
        currentUrl = url; lastHtml = null; pendingHtml = null
        pendingWake = false; contentRendering = true
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
        // 界面是否真的可见：hasContent=true 但 foreground=false 表示内容已排队/暂不可见
        put("foreground", JsonPrimitive(App.foreground))
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

    /** 字号倍率 → WebSettings.textZoom 百分比（50%–300%） */
    private fun zoomPercent(multiplier: Float): Int = (multiplier * 100).toInt().coerceIn(50, 300)

    /** 当前应生效的 textZoom 百分比；WebView 创建时用它回放 */
    fun currentZoomPercent(): Int =
        zoomPercent(get("fontScale").toFloatOrNull()?.coerceIn(0.5f, 3f) ?: 1f)

    /**
     * 把字号倍率真正作用到投送页面。
     *
     * 以前 `fontScale` 只写 SharedPreferences，WebView 侧从未读过（`textZoom` 写死在 100），
     * 而 `/info.capabilities` 又申报了这项能力 —— agent 调完拿到 ok，页面一个字都不变。
     * `textZoom` 是文字缩放百分比，对页面里写死的 px 字号同样生效，不要求页面用 rem/em。
     */
    private fun applyTextZoom(multiplier: Float) {
        val wv = App.webView ?: return
        val zoom = zoomPercent(multiplier)
        wv.post { wv.settings.textZoom = zoom }
        lastDbg = "fontScale=$multiplier textZoom=$zoom"
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
                    Ui.setWindowBrightness(a, f)
                    lastDbg += " applied; screenBrightness=${a.window.attributes.screenBrightness}"
                }
            }
            true
        }
        "fontScale" -> {
            val f = value.toFloatOrNull()
            if (f == null || f < 0.5f || f > 3f) false
            else {
                sp.edit().putString(key, value).apply()
                applyTextZoom(f)
                true
            }
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
