package com.aicast.app

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val hasContent = mutableStateOf(false)
    private val showSettings = mutableStateOf(false)
    private val showHistory = mutableStateOf(false)
    private val historyVersion = mutableStateOf(0) // 清空/进入历史时 +1，强制刷新列表
    // 设置页关闭时 +1，让时钟页重新读 Prefs（名称/模式/权限变了要反映到界面）
    private val settingsVersion = mutableStateOf(0)
    // 主题切换时 +1：驱动 Compose 在"原生时钟 / HTML 主题占位"间重选，并触发 syncLayers 重载
    private val themeVersion = mutableStateOf(0)
    private var webView: WebView? = null
    private var homeControls: LinearLayout? = null   // HTML 主题下浮层的"设置/历史"入口（原生主题隐藏）
    // 已载入的家模式主题 id：避免每次开关设置页都把整屏主题重载一遍（丢状态 + 刷屏 system.page）
    private var homeLoadedTheme: String? = null

    private val requestNotificationPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 没授权也能跑，只是看不到常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.ctx = applicationContext
        App.activity = this
        Prefs.init(applicationContext)
        AiCastService.ensure(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = aiCastColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 安全区不由这一层统一避让：待机面板（原生时钟/HTML 主题）本来就贴边显示；
                    // 设置页/历史页各自在「背景铺满之后」再避让切口，否则避让带会露出
                    // 主题纯黑，和页面底色拼出一条色差带。
                    Box(modifier = Modifier.fillMaxSize()) {
                        settingsVersion.value // 订阅：设置页关闭后重组
                        historyVersion.value // 订阅：历史清空后重组
                        themeVersion.value   // 订阅：主题切换后重组
                        if (showSettings.value) {
                            SettingsScreen(
                                onClose = {
                                    showSettings.value = false
                                    settingsVersion.value++
                                    syncLayers()
                                },
                                onChanged = { NsdAdvertiser.restart(applicationContext) }
                            )
                        } else if (hasContent.value) {
                            // 内容显示中：WebView 覆盖层负责呈现，Compose 层留空
                        } else if (showHistory.value) {
                            HistoryScreen(
                                entries = History.list(),
                                onReplay = { e ->
                                    // url 类历史重播 = 重新加载那个地址（页面是第三方资源，不缓存内容）
                                    val u = e.url
                                    if (u != null) CommandBus.loadUrl(u, false)
                                    else e.html?.let { CommandBus.renderHtml(it) }
                                    showHistory.value = false
                                },
                                onDelete = { History.remove(it.id); historyVersion.value++ },
                                onClear = { History.clear(); historyVersion.value++ },
                                onBack = { showHistory.value = false; syncLayers() }
                            )
                        } else if (Themes.isHtml(Prefs.theme)) {
                            // HTML 主题首页：WebView（更上层的内容视图）渲染主题，Compose 层留黑底占位
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ComposeColor.Black)
                            )
                        } else {
                            ClockScreen(
                                onSettings = {
                                    showSettings.value = true
                                    syncLayers()
                                },
                                onHistory = {
                                    showHistory.value = true
                                    historyVersion.value++
                                }
                            )
                        }
                    }
                }
            }
        }

        // WebView 必须直接挂在窗口内容层：放进 Compose AndroidView 时
        // Chromium 拿不到视口高度，vh/媒体查询全部为 0（实测 Mi 10 / WebView 116）
        val wv = createWebView(this)
        addContentView(
            wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView = wv
        buildHomeControls(this)   // 在 WebView 之后 add，浮于主题页之上
        syncLayers()

        HomeRefresh.request = { themeVersion.value++; syncLayers() }
        CommandBus.onCleared = {
            runOnUiThread { hasContent.value = false; homeLoadedTheme = null; syncLayers() }
        }

        Ui.applyPersistedBrightness(this)   // 冷启动回放亮度，否则界面与 /param 报告不一致
        ensureNotificationPermission()
        CommandBus.flushPending()           // 把界面不在期间排队的投送补上

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 返回键直觉：设置页 → 关设置回主页；历史页 → 关历史回主页；
                // 有投送 → 清屏回待机；已在待机 → 才退出
                when {
                    showSettings.value -> {
                        showSettings.value = false
                        settingsVersion.value++
                        syncLayers()
                    }
                    showHistory.value -> {
                        showHistory.value = false
                        syncLayers()
                    }
                    hasContent.value -> CommandBus.clear()
                    else -> finish()
                }
            }
        })
    }

    /**
     * 四层互斥：投送内容(WebView) / 待机主题 / 设置页 / 历史页；WebView 为内容视图层，最上
     *
     * `contentRendering`：内容已下发给 WebView、onPageFinished 还没到。这段窗口里
     * `hasContent` 仍是 false，若照常走分支就会把待机主题 load 到内容之后，屏幕被主题占掉。
     */
    private fun syncLayers() {
        val overlay = showSettings.value || showHistory.value
        val htmlHome = Themes.isHtml(Prefs.theme)
        val contentShowing = hasContent.value || CommandBus.isContentRendering()
        val show = !overlay && (contentShowing || htmlHome)
        webView?.visibility = if (show) View.VISIBLE else View.GONE
        homeControls?.visibility =
            if (htmlHome && !contentShowing && !overlay) View.VISIBLE else View.GONE
        if (show) {
            if (contentShowing) {
                // 投送内容已由 renderHtml/loadUrl 载入，直接聚焦收键；主题需下次重载
                homeLoadedTheme = null
                webView?.requestFocus(View.FOCUS_DOWN)
            } else {
                showHomeTheme()
            }
        }
    }

    /** Android 13+：前台服务的常驻通知必须被授权才可见（README 里说的"点允许"，代码之前没申请） */
    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        window.decorView.post { runCatching { requestNotificationPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
    }

    /** 把当前选中的 HTML 主题作为待机首页载入 WebView（家模式，不计入投送内容） */
    private fun showHomeTheme() {
        val id = Prefs.theme
        // 同一个主题已经在 WebView 里了就别再重载：否则开关设置页/历史页都会把整屏重刷一遍
        if (homeLoadedTheme == id) {
            webView?.requestFocus(View.FOCUS_DOWN)
            return
        }
        Themes.htmlFor(id)?.let { CommandBus.showHome(it); homeLoadedTheme = id }
        webView?.requestFocus(View.FOCUS_DOWN)
    }

    /**
     * HTML 主题会铺满 WebView（在最上层），原生时钟的齿轮/右滑入口被盖住。
     * 补一层极小的原生浮标"设置/历史"，仅在 HTML 待机且无投送时可见；
     * 电视端也可靠 D-pad 聚焦到它（原生待机仍用右滑/齿轮）。
     */
    private fun buildHomeControls(ctx: android.content.Context) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        row.addView(homeChip(ctx, Str.get(R.string.action_history)) {
            showHistory.value = true; historyVersion.value++; syncLayers()
        })
        row.addView(homeChip(ctx, Str.get(R.string.action_settings)) {
            showSettings.value = true; syncLayers()
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        addContentView(row, lp)
        homeControls = row
    }

    private fun homeChip(ctx: android.content.Context, label: String, onClick: () -> Unit): TextView {
        val pad = dp(10)
        // 焦点必须有可见反馈：浮标能聚焦但看不出来，等于没法操作
        fun bg(focused: Boolean) = GradientDrawable().apply {
            setColor(Color.parseColor(if (focused) "#E62A323D" else "#80151A21"))
            cornerRadius = dp(20).toFloat()
            if (focused) setStroke(dp(2), Color.parseColor("#4CC2A5"))
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#E6EAF0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad / 2 + dp(2), pad * 2, pad / 2 + dp(2))
            background = bg(false)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus -> v.background = bg(hasFocus) }
            setOnClickListener { onClick() }
            val mLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            mLp.marginStart = dp(8)
            layoutParams = mLp
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val row = homeControls
            // 焦点是否已落在右上角浮标上（浮标只在 HTML 待机主题下可见）
            val onChips = row != null && row.visibility == View.VISIBLE && row.findFocus() != null

            if (!hasContent.value && !showSettings.value && !showHistory.value) {
                // 电视端待机：方向键「左」→ 投送历史（触摸端用手势右滑）
                // 焦点已在浮标上时不抢：那时左右键要在「历史/设置」两枚浮标之间移动
                if (!onChips && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    showHistory.value = true
                    historyVersion.value++
                    syncLayers()   // HTML 主题下需把最上层 WebView 隐藏，历史页才露得出来
                    return true
                }
                // 电视端待机：MENU 键 → 设置（确定性入口，不依赖焦点漫游到浮标）
                if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                    showSettings.value = true
                    syncLayers()
                    return true
                }

                // HTML 待机主题：WebView 会把方向键吃掉（禁缩放只挡住了缩放平移，页内导航仍会吞键），
                // 焦点永远漫游不到最上层这两枚浮标。在 Activity 层接管（早于焦点视图）：
                //   上/右 → 浮标「历史」；浮标上按 下 → 交回 WebView
                if (row != null && row.visibility == View.VISIBLE) {
                    if (!onChips &&
                        (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                        row.childCount > 0
                    ) {
                        row.getChildAt(0).requestFocus()
                        return true
                    }
                    if (onChips && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        webView?.requestFocus(View.FOCUS_DOWN)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun createWebView(ctx: android.content.Context): WebView =
        WebView(ctx).apply {
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                WebView.setWebContentsDebuggingEnabled(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            // 首页 baseURL 是 https://aicast.local，媒体走 http://<ip>:43210/media 属混合内容；
            // 不放行则 <audio>/<img> 静默加载失败（局域网兜底媒体线的关键一步）
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 显示面板不是浏览器：禁掉捏合/文本缩放。
            // 否则电视遥控器方向键会进入缩放平移模式把整页推来推去，手机还能双指缩放
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // 字号倍率是真实能力：/param fontScale 改的是它（以前写死 100，agent 调了没反应）
            settings.textZoom = Params.currentZoomPercent()
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // 桥默认挂上（首页主题要用）；url 投送前由 CommandBus.loadUrl 摘掉
            CommandBus.attachBridge(this)
            webChromeClient = object : android.webkit.WebChromeClient() {
                // 没有 WebChromeClient 时 agent 页面里的 console/alert 全静默丢失，排障全靠猜
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i(
                        "aicast-web",
                        "[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}"
                    )
                    return false
                }
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url == "about:blank") return  // 清屏，不算一次页面事件
                    injectSdk(view)
                    view.requestFocus(View.FOCUS_DOWN)
                    // 这次加载是"投送内容"还是"待机主题"，由页面自己带来的标记决定。
                    // 不能拿全局 CommandBus.hasContent() 猜：主题页晚于内容页完成时会把状态判反
                    // （/state 报 hasContent=true，屏幕上是时钟）。url 投送的第三方页没标记 → 按内容算。
                    view.evaluateJavascript("window.__AICAST_KIND||''") { raw ->
                        if (raw?.trim('"') != CommandBus.KIND_THEME) {
                            CommandBus.markContentLoaded()
                            hasContent.value = true
                            syncLayers()
                            CommandBus.emit(
                                "system.page",
                                """{"url":${JSONObject.quote(url ?: "")}}"""
                            )
                        } else {
                            // 主题页：不碰 contentRendering —— 若同时有一次内容加载在途，
                            // 要等内容页自己的 onPageFinished 来收尾（否则主题会把内容盖掉）。
                            // 主题重载不刷 system.page，只有真实投送才记。
                            if (!CommandBus.hasContent()) hasContent.value = false
                            syncLayers()
                        }
                    }
                }

                // 以前 URL 投送失败屏幕一片空白、agent 毫不知情：现在发 page.error 事件
                override fun onReceivedError(
                    view: WebView, request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    CommandBus.markLoadFailed()
                    CommandBus.emit(
                        "page.error",
                        JSONObject()
                            .put("url", request.url?.toString() ?: "")
                            .put("code", runCatching { error.errorCode }.getOrDefault(-1))
                            .put(
                                "message",
                                runCatching { error.description?.toString() }.getOrNull()
                                    ?: Str.get(R.string.err_load_failed)
                            )
                            .toString()
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView, request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (!request.isForMainFrame) return
                    CommandBus.markLoadFailed()
                    CommandBus.emit(
                        "page.error",
                        JSONObject()
                            .put("url", request.url?.toString() ?: "")
                            .put("code", errorResponse.statusCode)
                            .put("message", "HTTP ${errorResponse.statusCode}")
                            .toString()
                    )
                }

                // 挡掉 javascript:/file:/content: 等非网页 scheme（渲染 url 已在 server 侧校验，这里是双保险）
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: android.webkit.WebResourceRequest
                ): Boolean {
                    val s = request.url?.scheme ?: return false
                    return !s.equals("http", true) && !s.equals("https", true)
                }
            }
            App.webView = this
        }

    // 沉浸态必须显式设 STICKY：Android 7~10 默认行为下用户一触屏状态栏就永久回来，
    // 状态栏一回来就会压住待机页顶部那排入口；Flyme 还需在重获焦点时重施
    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onResume() {
        super.onResume()
        App.activity = this
        applyImmersive()
    }

    /**
     * 可见性必须单独记：退到桌面时 Activity 不销毁、WebView 还在，往里面投内容
     * 用户是看不见的。`/render` 靠它决定回 200 还是 202（见 CommandBus.renderHtml）。
     */
    override fun onStart() {
        super.onStart()
        App.foreground = true
        CommandBus.flushPending()   // 后台期间排队的投送，回到前台立刻补上
    }

    override fun onStop() {
        App.foreground = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        App.foreground = false
        if (HomeRefresh.request != null) HomeRefresh.request = null
        if (App.activity === this) App.activity = null
        App.webView = null
        webView?.destroy()
        webView = null
    }

    private fun injectSdk(view: WebView) {
        try {
            val text = assets.open("aicast-sdk.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(text, null)
        } catch (e: Exception) { /* asset 缺失则跳过 */ }
    }
}
