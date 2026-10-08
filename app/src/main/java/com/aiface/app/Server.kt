package com.aiface.app

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 内置 HTTP server（§5，M1 子集）：/health /info /render /message /param /state /events */
private const val MAX_BODY_BYTES = 4 * 1024 * 1024
private val PUBLIC_PATHS = setOf("/health", "/info", "/auth", "/history", "/favicon.ico")

object Server {
    const val PORT = 43210

    fun start(): Any = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        // auth 模式统一门禁（§4/§5）：/health /info /auth 公开，其余需 Bearer token
        intercept(ApplicationCallPipeline.Plugins) {
            val p = call.request.path()
            // 媒体读取公开：设备 WebView 的 <audio>/<img> 带不了 token，
            // 且页面内容本身即公开面；写（上传/删除）仍走 token + render 权限
            val mediaGet = p.startsWith("/media/") && call.request.httpMethod == HttpMethod.Get
            val gateOpen = mediaGet || p in PUBLIC_PATHS || Prefs.mode != "auth" || !Prefs.hasPassword()
            if (!gateOpen) {
                val tok = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                    ?.ifEmpty { null }
                    ?: call.request.queryParameters["token"]
                if (tok == null || !Auth.valid(tok)) {
                    call.respond(HttpStatusCode.Unauthorized, err("unauthorized", "需先 POST /auth {password} 换取 token"))
                    finish()
                }
            }
        }
        routing {

            get("/health") { call.respondText("ok") }

            get("/info") { call.respond(DeviceInfo.json()) }

            post("/auth") {
                if (Prefs.mode != "auth" || !Prefs.hasPassword())
                    return@post call.respond(
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("type", JsonPrimitive("public"))
                            put("message", JsonPrimitive("该设备为公开模式，无需 token"))
                        }
                    )
                if (Auth.locked())
                    return@post call.respond(
                        HttpStatusCode.TooManyRequests, err("locked", "密码错误次数过多，请 60 秒后再试")
                    )
                val body = try { receiveLimited() ?: "{}" }
                    catch (e: BadBody) {
                        return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
                    }
                val pwd = parseObj(body)?.str("password")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 {password}"))
                if (pwd.isNotEmpty() && Prefs.checkPassword(pwd)) {
                    Auth.noteSuccess()
                    val (t, exp) = Auth.issue()
                    CommandBus.emit("auth.granted", "{}")
                    call.respond(buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("type", JsonPrimitive("token"))
                        put("token", JsonPrimitive(t))
                        put("expiresAt", JsonPrimitive(exp))
                        put("ts", JsonPrimitive(System.currentTimeMillis()))
                    })
                } else {
                    val nowLocked = Auth.noteWrongPassword()
                    CommandBus.emit("auth.denied", "{}")
                    call.respond(
                        if (nowLocked) HttpStatusCode.TooManyRequests else HttpStatusCode.Unauthorized,
                        err(if (nowLocked) "locked" else "wrong_password", "密码错误")
                    )
                }
            }

            post("/render") {
                if (!Prefs.isOpen("render"))
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        err("permission_denied", "该设备未开放 render，请到 aiface 设置开启")
                    )
                val body = try { receiveLimited() }
                    catch (e: BadBody) {
                        return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
                    }
                    ?: return@post call.respond(
                        HttpStatusCode.PayloadTooLarge, err("payload_too_large", "上限 4MB")
                    )
                val obj = parseObj(body)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", "body 需为 JSON 对象"))
                val html = obj.str("html")
                val url = obj.str("url")
                val wake = obj.bool("wake")
                when {
                    html != null -> {
                        CommandBus.renderHtml(html)
                        History.add(html, call.request.local.remoteHost)
                        call.respond(ok("render"))
                    }
                    url != null -> { CommandBus.loadUrl(url, wake); call.respond(ok("render")) }
                    else -> { CommandBus.clear(); call.respond(ok("idle")) } // 空内容 = 回待机
                }
            }

            get("/history") {
                call.respondText(History.listJson(), contentType = ContentType.Application.Json)
            }

            // 主题列表 + 当前项（供 agent 发现可切换/已注册的首页主题）
            get("/themes") {
                call.respondText(Themes.listJson(), contentType = ContentType.Application.Json)
            }

            // 注册一个 agent 自定义 HTML 首页主题；activate=true 立即设为首页
            post("/theme") {
                if (!Prefs.isOpen("render"))
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        err("permission_denied", "该设备未开放 render（注册主题复用投送权限）")
                    )
                val body = try { receiveLimited() }
                    catch (e: BadBody) {
                        return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
                    }
                    ?: return@post call.respond(
                        HttpStatusCode.PayloadTooLarge, err("payload_too_large", "上限 4MB")
                    )
                val obj = parseObj(body)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", "body 需为 JSON 对象"))
                val html = obj.str("html")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 {html}"))
                val meta = try {
                    Themes.register(html, obj.str("name"), obj.str("id"))
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, err("bad_theme", e.message ?: ""))
                }
                if (obj.bool("activate")) {
                    Prefs.theme = meta.id
                    HomeRefresh.invalidate()
                }
                call.respond(
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("type", JsonPrimitive("theme"))
                        put("id", JsonPrimitive(meta.id))
                        put("name", JsonPrimitive(meta.name))
                        put("activated", JsonPrimitive(obj.bool("activate")))
                        put("ts", JsonPrimitive(System.currentTimeMillis()))
                    }
                )
            }

            delete("/theme") {
                val id = call.request.queryParameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 ?id="))
                val removed = Themes.remove(id)
                if (removed) HomeRefresh.invalidate()
                call.respond(buildJsonObject {
                    put("ok", JsonPrimitive(removed))
                    put("type", JsonPrimitive("theme"))
                    put("removed", JsonPrimitive(id))
                })
            }

            // ---------- 媒体（§5 /media）：局域网媒体兜底，见 Media.kt 注释 ----------

            // 上传：raw body 流式落盘（需 Content-Length），?name= 提供原名；复用 render 权限
            post("/media") {
                if (!Prefs.isOpen("render"))
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        err("permission_denied", "该设备未开放 render（媒体上传复用投送权限）")
                    )
                val len = call.request.headers["Content-Length"]?.toLongOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.LengthRequired, err("length_required", "上传需带 Content-Length")
                    )
                if (len <= 0) return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", "空上传"))
                if (len > Media.MAX_SINGLE_BYTES)
                    return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        err("payload_too_large", "媒体上限 ${Media.MAX_SINGLE_BYTES / 1024 / 1024}MB")
                    )
                if (!Media.hasRoom(len))
                    return@post call.respond(
                        HttpStatusCode.InsufficientStorage,
                        err("no_space", "设备存储不足（已给系统盘留底）；请把媒体投到存储宽裕的设备")
                    )
                val name = call.request.queryParameters["name"] ?: "media.mp3"
                val ct = call.request.contentType()
                val type = if (ct.contentType == "*" || ct.contentSubtype == "*")
                    "application/octet-stream" else ct.toString()
                val tmp = File(App.ctx.cacheDir, "media_up_${System.nanoTime()}")
                try {
                    // 流式落盘不进内存；超限由 Content-Length 预检 + Media.commit 复核兜底
                    val copied = java.io.FileOutputStream(tmp).use { out ->
                        call.receiveChannel().copyTo(out)
                    }
                    if (copied > Media.MAX_SINGLE_BYTES) {
                        tmp.delete()
                        return@post call.respond(
                            HttpStatusCode.PayloadTooLarge, err("payload_too_large", "媒体超过单文件上限")
                        )
                    }
                    if (copied != len) {
                        tmp.delete()
                        return@post call.respond(
                            HttpStatusCode.BadRequest, err("incomplete", "body 与 Content-Length 不一致")
                        )
                    }
                    val meta = Media.commit(name, type, tmp)
                    CommandBus.emit(
                        "media.add",
                        org.json.JSONObject().put("id", meta.id).put("name", meta.name)
                            .put("size", meta.size).toString()
                    )
                    call.respond(buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("type", JsonPrimitive("media"))
                        put("id", JsonPrimitive(meta.id))
                        put("url", JsonPrimitive("/media/${meta.id}"))
                        put("size", JsonPrimitive(meta.size))
                        put("ts", JsonPrimitive(System.currentTimeMillis()))
                    })
                } catch (e: Exception) {
                    tmp.delete()
                    call.respond(
                        HttpStatusCode.InsufficientStorage, err("media_failed", e.message ?: "媒体写入失败")
                    )
                }
            }

            get("/media") {
                call.respondText(Media.listJson(), contentType = ContentType.Application.Json)
            }

            delete("/media") {
                val id = call.request.queryParameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 ?id="))
                val removed = Media.remove(id)
                call.respond(buildJsonObject {
                    put("ok", JsonPrimitive(removed))
                    put("type", JsonPrimitive("media"))
                    put("removed", JsonPrimitive(id))
                })
            }

            // 拉流：PartialContent 插件让 respondFile 支持 Range（边下边播 + seek）；读取公开
            // 拉流：手动实现 Range（2.3.12 无 PartialContent 构件）——音频边下边播 + 拖动 seek 的关键
            get("/media/{id}") {
                val id = java.net.URLDecoder.decode(
                    call.request.path().substringAfterLast('/'), "UTF-8"
                )
                val meta = Media.get(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, err("not_found", "无此媒体: $id"))
                val f = Media.fileOf(meta)
                    ?: return@get call.respond(HttpStatusCode.NotFound, err("gone", "媒体文件已被清理"))
                val ctype = try {
                    ContentType.parse(meta.type)
                } catch (e: Exception) {
                    ContentType.Application.OctetStream
                }
                val total = f.length()
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                val rm = call.request.headers["Range"]?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                if (rm == null) {
                    call.respond(LocalFileContent(f, contentType = ctype))
                    return@get
                }
                var start = rm.groupValues[1].toLongOrNull()
                var end = rm.groupValues[2].toLongOrNull()
                if (start == null && end != null) { start = (total - end).coerceAtLeast(0) ; end = null } // 后缀区间 bytes=-N
                val s = start ?: 0L
                val e2 = (end ?: (total - 1)).coerceAtMost(total - 1)
                if (s > e2 || s >= total) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$total")
                    return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, err("bad_range", "区间超出"))
                }
                call.response.header(HttpHeaders.ContentRange, "bytes $s-$e2/$total")
                call.response.header(HttpHeaders.ContentLength, (e2 - s + 1).toString())
                call.respondBytesWriter(ctype, HttpStatusCode.PartialContent) {
                    RandomAccessFile(f, "r").use { raf ->
                        raf.seek(s)
                        val buf = ByteArray(64 * 1024)
                        var remaining = e2 - s + 1
                        while (remaining > 0) {
                            val n = raf.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                            if (n <= 0) break
                            writeFully(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
            }

            post("/message") {
                if (!Prefs.isOpen("message"))
                    return@post call.respond(
                        HttpStatusCode.Forbidden, err("permission_denied", "该设备未开放 message")
                    )
                val body = try { receiveLimited() ?: "{}" }
                    catch (e: BadBody) {
                        return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
                    }
                CommandBus.deliverMessage(body)
                call.respond(ok("message"))
            }

            get("/state") { call.respond(CommandBus.stateJson()) }

            post("/param") {
                if (!Prefs.isOpen("param"))
                    return@post call.respond(
                        HttpStatusCode.Forbidden, err("permission_denied", "该设备未开放 param")
                    )
                val obj = try { parseObj(receiveLimited() ?: "{}") }
                    catch (e: BadBody) {
                        return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
                    }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", ""))
                val k = obj.str("key")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 key"))
                val v = obj.str("value")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", "需要 value(字符串)")  )
                if (Params.set(k, v)) call.respond(ok("param"))
                else if (k == "theme") call.respond(
                    HttpStatusCode.BadRequest,
                    err(
                        "unknown_theme",
                        "无此主题: $v（可用: " + Themes.list().joinToString("/") { it.id } + "）"
                    )
                )
                else call.respond(
                    HttpStatusCode.BadRequest,
                    err("unknown_param", "不支持: $k（可用 brightness/theme/fontScale/volume/rotationLock/idleDimSeconds）")
                )
            }

            get("/param") {
                call.respond(buildJsonObject {
                    listOf("brightness", "theme", "fontScale", "volume").forEach {
                        put(it, JsonPrimitive(Params.get(it)))
                    }
                })
            }

            // long-poll：有事件立即返回，否则挂到 timeout（默认 25s）
            get("/events") {
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val timeout = (call.request.queryParameters["timeout"]?.toLongOrNull() ?: 25L)
                    .coerceIn(0L, 60L)
                val deadline = System.currentTimeMillis() + timeout * 1000
                var out = CommandBus.eventsSince(since)
                while (out == "[]" && System.currentTimeMillis() < deadline) {
                    delay(500)
                    out = CommandBus.eventsSince(since)
                }
                call.respondText(out, contentType = ContentType.Application.Json)
            }
        }
    }.also { it.start(wait = false) }
}

// ---------- helpers ----------

private suspend fun PipelineContext<*, io.ktor.server.application.ApplicationCall>.receiveLimited(): String? {
    val len = call.request.headers["Content-Length"]?.toLongOrNull()
    if (len != null && len > MAX_BODY_BYTES) return null
    return try {
        call.receiveText()
    } catch (e: io.ktor.utils.io.charsets.MalformedInputException) {
        throw BadBody("body 必须是 UTF-8 编码的 JSON")
    }
}

/** 客户端报文级错误 → 400 */
private class BadBody(msg: String) : Exception(msg)

private fun parseObj(s: String): JsonObject? =
    try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun err(code: String, msg: String) = buildJsonObject {
    put("ok", JsonPrimitive(false))
    put("code", JsonPrimitive(code))
    put("message", JsonPrimitive(msg))
}

private fun ok(type: String) = buildJsonObject {
    put("ok", JsonPrimitive(true))
    put("type", JsonPrimitive(type))
    put("ts", JsonPrimitive(System.currentTimeMillis()))
}
