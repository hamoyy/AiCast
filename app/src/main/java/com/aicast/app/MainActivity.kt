package com.aicast.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Calendar

/** 昼夜状态重新判定的间隔：时段是分钟级的，30 秒足够，又不至于频繁改窗口属性 */
private const val SCREEN_TICK_MS = 30_000L

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

    /** 当前是否处于「夜间熄屏」状态；null = 本次启动还没判定过 */
    private var screenOffActive: Boolean? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val screenTick = object : Runnable {
        override fun run() {
            applyScreenState()
            uiHandler.postDelayed(this, SCREEN_TICK_MS)
        }
    }

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
        // 屏幕常亮不在这里硬加了：夜间熄屏时段要能把它摘掉，统一交给 applyScreenState() 管

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
                                onChanged = {
                                    NsdAdvertiser.restart(applicationContext)
                                    // 屏幕时段可能刚被改过，立刻重判一次，不用等下一次 tick
                                    applyScreenState()
                                }
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

        // 冷启动先按当前时间判定一次（顺带把持久化的亮度回放上），再起定时器跟着时间走
        applyScreenState()
        uiHandler.postDelayed(screenTick, SCREEN_TICK_MS)
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

    /**
     * 按当前时间应用「夜间熄屏」状态。
     *
     * 时段内：摘掉「保持屏幕常亮」+ 亮度压到最低。**注意这不是"强制关屏"**——
     * 普通 app 没有这个权限（goToSleep 要系统签名、lockNow 要设备管理员，而本项目
     * 承诺不碰设备管理员）。松开常亮后，屏幕由系统自己的「屏幕超时」关掉，
     * 所以**需要用户把设备的熄屏超时设短**（15~30 秒），否则要等很久才黑。
     *
     * 时段外：恢复常亮 + 用户设定的亮度；若刚跨出时段且屏幕已被系统熄掉，
     * 主动把屏幕点回来。
     *
     * 状态没变就直接返回——每 30 秒都动一次窗口属性会把界面刷得很难看。
     */
    private fun applyScreenState() {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val off = ScreenSchedule.inOffWindow(now, Prefs.screenOffFrom, Prefs.screenOffTo)
        if (off == screenOffActive) return
        val leavingNight = screenOffActive == true
        screenOffActive = off
        if (off) {
            Ui.setKeepAwake(this, false)
            Ui.setWindowBrightness(this, 0.01f)   // 立刻变暗；LCD 背光仍亮，真熄屏靠系统超时
        } else {
            Ui.setKeepAwake(this, true)
            Ui.applyPersistedBrightness(this)
            if (leavingNight && !Ui.isScreenOn()) wakeScreen()
        }
    }

    /**
     * 把屏幕点回来。系统熄屏后本 Activity 已是 stopped 状态，光加 FLAG_TURN_SCREEN_ON
     * 不会生效，得把它拉回前台。singleTask + SINGLE_TOP 走 onNewIntent，页面不会被重建。
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        startActivity(
            android.content.Intent(this, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
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
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(JsBridge(), "AndroidAiCast")
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(screenTick)
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
