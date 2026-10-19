package com.aicast.app

import android.app.Activity
import android.content.Context
import android.webkit.WebView

object App {
    lateinit var ctx: Context
        internal set
    @Volatile var activity: Activity? = null
    @Volatile var webView: WebView? = null

    /**
     * 界面是否真的可见（Activity 已 start、未 stop）。
     *
     * 为什么必须单独记一个：退到桌面时 Activity **不销毁**，`webView` 照样存在，
     * 往里面 `loadDataWithBaseURL` 也能成功——但用户眼前是桌面。
     * 只看 `webView != null` 就会把这种投送当成"已上屏"回报给 agent（假成功）。
     */
    @Volatile var foreground: Boolean = false

    /**
     * ctx 是否已赋值。必须在 App 内部问：`ctx` 的 setter 是 internal，
     * 外部写 `App::ctx.isInitialized` 会因拿不到 backing field 直接编译不过。
     * 取文案的代码路径（server/service 线程）可能早于 Activity 起来，需要这个判断兜底。
     */
    fun isReady(): Boolean = ::ctx.isInitialized
}
