package com.aicast.app

import android.app.Activity
import android.content.Context
import android.webkit.WebView

object App {
    lateinit var ctx: Context
        internal set
    @Volatile var activity: Activity? = null
    @Volatile var webView: WebView? = null
}
