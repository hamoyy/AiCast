package com.aiface.app

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
import androidx.compose.foundation.layout.safeDrawingPadding
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
    private var homeControls: View? = null   // HTML 主题下浮层的"设置/历史"入口（原生主题隐藏）
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
        AifaceService.ensure(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = aifaceColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                    ) {
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
                                    e.html?.let { CommandBus.renderHtml(it) }
                                    showHistory.value = false
                                },
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

    /** 四层互斥：投送内容(WebView) / 待机主题 / 设置页 / 历史页；WebView 为内容视图层，最上 */
    private fun syncLayers() {
        val overlay = showSettings.value || showHistory.value
        val htmlHome = Themes.isHtml(Prefs.theme)
        val show = !overlay && (hasContent.value || htmlHome)
        webView?.visibility = if (show) View.VISIBLE else View.GONE
        homeControls?.visibility =
            if (htmlHome && !hasContent.value && !overlay) View.VISIBLE else View.GONE
        if (show) {
            if (hasContent.value) {
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
        row.addView(homeChip(ctx, "历史") {
            showHistory.value = true; historyVersion.value++; syncLayers()
        })
        row.addView(homeChip(ctx, "设置") {
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
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#E6EAF0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad / 2 + dp(2), pad * 2, pad / 2 + dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#80151A21"))
                cornerRadius = dp(20).toFloat()
            }
            isFocusable = true
            isClickable = true
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
        if (event.action == KeyEvent.ACTION_DOWN &&
            !hasContent.value && !showSettings.value && !showHistory.value
        ) {
            // 电视端待机：方向键「左」→ 投送历史（触摸端用手势右滑）
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
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
            // 首页 baseURL 是 https://aiface.local，媒体走 http://<ip>:43210/media 属混合内容；
            // 不放行则 <audio>/<img> 静默加载失败（局域网兜底媒体线的关键一步）
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 显示面板不是浏览器：禁掉捏合/文本缩放。
            // 否则电视遥控器方向键会进入缩放平移模式把整页推来推去，手机还能双指缩放
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(JsBridge(), "AndroidAiface")
            webChromeClient = object : android.webkit.WebChromeClient() {
                // 没有 WebChromeClient 时 agent 页面里的 console/alert 全静默丢失，排障全靠猜
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i(
                        "aiface-web",
                        "[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}"
                    )
                    return false
                }
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url == "about:blank") return  // 清屏，不算一次页面事件
                    injectSdk(view)
                    val isContent = CommandBus.hasContent()
                    if (isContent) { hasContent.value = true; syncLayers() }
                    view.requestFocus(View.FOCUS_DOWN)
                    // 主题重载不再刷 system.page 事件；只有真实投送才记
                    if (isContent) CommandBus.emit("system.page", """{"url":${JSONObject.quote(url ?: "")}}""")
                }

                // 以前 URL 投送失败屏幕一片空白、agent 毫不知情：现在发 page.error 事件
                override fun onReceivedError(
                    view: WebView, request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    CommandBus.emit(
                        "page.error",
                        JSONObject()
                            .put("url", request.url?.toString() ?: "")
                            .put("code", runCatching { error.errorCode }.getOrDefault(-1))
                            .put("message", runCatching { error.description?.toString() }.getOrNull() ?: "加载失败")
                            .toString()
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView, request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (!request.isForMainFrame) return
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
    // 叠加 safeDrawingPadding 的避让会在顶部堆出一条大空带；Flyme 还需在重获焦点时重施
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

    override fun onDestroy() {
        super.onDestroy()
        if (HomeRefresh.request != null) HomeRefresh.request = null
        if (App.activity === this) App.activity = null
        App.webView = null
        webView?.destroy()
        webView = null
    }

    private fun injectSdk(view: WebView) {
        try {
            val text = assets.open("aiface-sdk.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(text, null)
        } catch (e: Exception) { /* asset 缺失则跳过 */ }
    }
}
