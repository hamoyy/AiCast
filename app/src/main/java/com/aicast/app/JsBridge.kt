package com.aicast.app

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 页面 JS SDK → app 的桥（§9）：UI 事件写入环形缓冲，agent 经 /events 拉取。
 *
 * 页面是**不可信来源**：事件名走 [CommandBus.emitFromPage] 做字符集过滤与命名空间降级，
 * data 重新序列化后才入缓冲——页面无法伪造系统级（system / auth）事件，也无法破坏事件流结构。
 */
class JsBridge {

    @JavascriptInterface
    fun emit(type: String, data: String?) {
        val payloadJson: JsonElement = try {
            Json.parseToJsonElement(data ?: "null")
        } catch (e: Exception) {
            JsonNull
        }
        val payload = buildJsonObject {
            put("name", type.take(64))
            put("data", payloadJson)
        }.toString()
        CommandBus.emitFromPage(type, payload)
    }

    @JavascriptInterface
    fun getInfo(): String = try {
        DeviceInfo.json().toString()
    } catch (e: Exception) { "{}" }
}
