package com.aicast.app

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android 4.4 / 4.4W（API 19~20）的界面。
 *
 * 与 Compose 版 [MainActivity] **行为对齐、实现无关**：
 * - 同一个 [CommandBus]，同样的分层规则（投送内容 / 待机主题 / 设置 / 历史四层互斥）；
 * - 待机主题的 "native" 用 View 版时钟实现（Compose 时钟在 4.4 上不可用）；
 * - 设置/历史/更新全部用平台控件重写，不引用任何 Compose 类。
 *
 * 4.4 的坑都在这里处理掉了：WebView 的 mixedContentMode 是 API 21、onReceivedError 的
 * WebResourceRequest 版本是 API 23、evaluateJavascript 恰好是 19 —— 所以这里只重写
 * 低版本签名，绝不引用 WebResourceRequest。
 */
class LegacyActivity : Activity() {

    private lateinit var root: android.widget.FrameLayout
    private var webView: WebView? = null
    private var clock: View? = null
    private var clockTime: android.widget.TextView? = null
    private var clockDate: android.widget.TextView? = null
    private var clockStatus: android.widget.TextView? = null
    private var homeChips: android.widget.LinearLayout? = null
    private var panel: View? = null

    private var hasContent = false
    private var homeLoadedTheme: String? = null
    private var panelKind = NONE

    private val handler = Handler(Looper.getMainLooper())
    private val update by lazy { LegacyUpdate(this) }
    private val tick = object : Runnable {
        override fun run() {
            renderClock()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.ctx = applicationContext
        App.activity = this
        Prefs.init(applicationContext)
        AiCastService.ensure(applicationContext)

        applyImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        clock = buildClock(this).also { root.addView(it, matchParent()) }
        val wv = createWebView(this)
        root.addView(wv, matchParent())
        webView = wv
        homeChips = buildHomeChips(this).also {
            root.addView(
                it,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = LegacyUi.dp(this@LegacyActivity, 10)
                    rightMargin = LegacyUi.dp(this@LegacyActivity, 10)
                }
            )
        }
        setContentView(root)

        HomeRefresh.request = { syncLayers() }
        CommandBus.onCleared = {
            runOnUiThread { hasContent = false; homeLoadedTheme = null; syncLayers() }
        }

        Ui.applyPersistedBrightness(this)
        syncLayers()
        renderClock()
        handler.post(tick)

        CommandBus.flushPending()
        // 4.4 设备普遍是 2013~2014 年的机器，更新检查晚一点，别和首帧抢 CPU
        handler.postDelayed({ update.checkAutomatically() }, 8000)
    }

    // ---------------- 分层 ----------------

    /**
     * 与 Compose 版同一套规则：
     * 投送内容 → WebView 显示；待机主题 native → 时钟；待机主题 html → WebView 载主题；
     * 设置/历史页打开时把 WebView 收起（4.4 上 WebView 也是独立图层，会盖住面板）。
     */
    private fun syncLayers() {
        val overlay = panelKind != NONE || update.dialogVisible
        val htmlHome = Themes.isHtml(Prefs.theme)
        val contentShowing = hasContent || CommandBus.isContentRendering()

        webView?.visibility = if (!overlay && (contentShowing || htmlHome)) View.VISIBLE else View.GONE
        clock?.visibility = if (!overlay && !contentShowing && !htmlHome) View.VISIBLE else View.GONE
        // 待机（时钟或 HTML 主题）时给两个入口；投送内容/面板打开时收起，避免误触
        homeChips?.visibility = if (!overlay && !contentShowing) View.VISIBLE else View.GONE

        if (!overlay && contentShowing) {
            homeLoadedTheme = null
            webView?.requestFocus(View.FOCUS_DOWN)
        } else if (!overlay && htmlHome) {
            showHomeTheme()
        }
    }

    private fun showHomeTheme() {
        val id = Prefs.theme
        if (homeLoadedTheme == id) {
            webView?.requestFocus(View.FOCUS_DOWN)
            return
        }
        Themes.htmlFor(id)?.let {
            CommandBus.showHome(it)
            homeLoadedTheme = id
        }
        webView?.requestFocus(View.FOCUS_DOWN)
    }

    // ---------------- 待机时钟（View 版） ----------------

    private fun buildClock(ctx: Context): View {
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(LegacyUi.dp(ctx, 24), 0, LegacyUi.dp(ctx, 24), LegacyUi.dp(ctx, 40))
        }
        clockDate = LegacyUi.label(ctx, "", 16f, LegacyUi.dim).apply { gravity = Gravity.CENTER }
        clockTime = LegacyUi.label(ctx, "--:--", 76f).apply { gravity = Gravity.CENTER }
        clockStatus = LegacyUi.label(ctx, "", 14f, LegacyUi.dim).apply {
            gravity = Gravity.CENTER
            setPadding(0, LegacyUi.dp(ctx, 18), 0, 0)
        }
        box.addView(clockDate)
        box.addView(clockTime)
        box.addView(clockStatus)
        box.addView(LegacyUi.label(ctx, Str.get(R.string.legacy_clock_hint), 12f, Color.parseColor("#5C6672")).apply {
            gravity = Gravity.CENTER
            setPadding(0, LegacyUi.dp(ctx, 26), 0, 0)
        })
        return box
    }

    private fun renderClock() {
        val now = Date()
        val loc = Locale.getDefault()
        val timePattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clockTime?.text = SimpleDateFormat(timePattern, loc).format(now)
        val datePattern = if (Str.isEnglish()) "MMM d, EEEE" else "M月d日 EEEE"
        clockDate?.text = SimpleDateFormat(datePattern, loc).format(now)

        val mode = if (Prefs.mode == "auth") Str.get(R.string.mode_auth) else Str.get(R.string.mode_public)
        val accepting = if (Prefs.castEnabled) "" else " · " + Str.get(R.string.legacy_cast_off)
        clockStatus?.text = "${Prefs.name} · ${Net.localIp()}:${Server.port}\n$mode$accepting"
    }

    private fun buildHomeChips(ctx: Context): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(LegacyUi.chip(ctx, Str.get(R.string.action_history)) { openHistory() })
        row.addView(LegacyUi.chip(ctx, Str.get(R.string.action_settings)) { openSettings() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = LegacyUi.dp(ctx, 8) }
        })
        return row
    }

    // ---------------- 面板 ----------------

    private fun openSettings() {
        closePanel()
        val v = LegacyPanels.settings(
            this,
            update = update,
            onClose = { closePanel() },
            onRebuild = { openSettings() },
            onChanged = { NsdAdvertiser.restart(applicationContext) }
        )
        showPanel(v, SETTINGS)
    }

    private fun openHistory() {
        closePanel()
        val v = LegacyPanels.history(
            this,
            onReplay = { entry ->
                val u = entry.url
                if (u != null) CommandBus.loadUrl(u, false) else entry.html?.let { CommandBus.renderHtml(it) }
                closePanel()
            },
            onClose = { closePanel() },
            onRebuild = { openHistory() }
        )
        showPanel(v, HISTORY)
    }

    private fun showPanel(v: View, kind: Int) {
        panel?.let { root.removeView(it) }
        panel = v
        panelKind = kind
        root.addView(v, matchParent())
        v.requestFocus()
        syncLayers()
    }

    private fun closePanel() {
        panel?.let { root.removeView(it) }
        panel = null
        panelKind = NONE
        syncLayers()
    }

    // ---------------- 键位 ----------------

    override fun onBackPressed() {
        when {
            panelKind != NONE -> closePanel()
            update.dialogVisible -> update.dismiss()
            hasContent -> CommandBus.clear()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && panelKind == NONE && !update.dialogVisible) {
            if (!hasContent) {
                // 电视盒子：左方向键唤历史、MENU 键进设置（与 Compose 版一致）
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    openHistory(); return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                    openSettings(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 生命周期 ----------------

    override fun onStart() {
        super.onStart()
        App.foreground = true
        CommandBus.flushPending()
        handler.post(tick)
    }

    override fun onStop() {
        App.foreground = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        App.activity = this
        applyImmersive()
        renderClock()
        syncLayers()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        App.foreground = false
        if (HomeRefresh.request != null) HomeRefresh.request = null
        CommandBus.onCleared = null
        if (App.activity === this) App.activity = null
        App.webView = null
        webView?.destroy()
        webView = null
        update.dispose()
    }

    /** 4.4 已有 IMMERSIVE_STICKY（API 19），正好是它存在的意义：触屏后自动恢复全屏 */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun matchParent() = android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    // ---------------- WebView ----------------

    private fun createWebView(ctx: Context): WebView = WebView(ctx).apply {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            WebView.setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        // mixedContentMode 是 API 21；4.4 默认允许混合内容，不需要（也不能）设置
        if (Build.VERSION.SDK_INT >= 21) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = Params.currentZoomPercent()
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        CommandBus.attachBridge(this)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i("aicast-web", "[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return false
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url == "about:blank") return
                injectSdk(view)
                view.requestFocus(View.FOCUS_DOWN)
                // evaluateJavascript 是 API 19 引入的，4.4 可用
                view.evaluateJavascript("window.__AICAST_KIND||''") { raw ->
                    if (raw?.trim('"') != CommandBus.KIND_THEME) {
                        CommandBus.markContentLoaded()
                        hasContent = true
                        syncLayers()
                        CommandBus.emit("system.page", """{"url":${JSONObject.quote(url ?: "")}}""")
                    } else {
                        if (!CommandBus.hasContent()) hasContent = false
                        syncLayers()
                    }
                }
            }

            // 4.4 只有这个旧签名；API 23 的 WebResourceRequest 版本在这里不能出现
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                CommandBus.markLoadFailed()
                CommandBus.emit(
                    "page.error",
                    JSONObject().put("url", failingUrl ?: "")
                        .put("code", errorCode)
                        .put("message", description ?: Str.get(R.string.err_load_failed))
                        .toString()
                )
            }

            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                val u = url ?: return false
                return !(u.startsWith("http://", true) || u.startsWith("https://", true))
            }
        }
        App.webView = this
    }

    private fun injectSdk(view: WebView) {
        try {
            val text = assets.open("aicast-sdk.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(text, null)
        } catch (e: Exception) { /* asset 缺失则跳过 */ }
    }

    companion object {
        private const val NONE = 0
        private const val SETTINGS = 1
        private const val HISTORY = 2
    }
}
