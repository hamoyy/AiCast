package com.aiface.app

import android.webkit.JavascriptInterface
import org.json.JSONObject

/** 页面 JS SDK → app 的桥（§9）：UI 事件写入环形缓冲，agent 经 /events 拉取 */
class JsBridge {

    @JavascriptInterface
    fun emit(type: String, data: String?) {
        val payload = """{"name":${JSONObject.quote(type)},"data":${data ?: "null"}}"""
        CommandBus.emit(if (type.contains('.')) type else "ui.$type", payload)
    }

    @JavascriptInterface
    fun getInfo(): String = try {
        DeviceInfo.json().toString()
    } catch (e: Exception) { "{}" }
}
