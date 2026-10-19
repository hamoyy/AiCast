package com.aicast.app

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.net.URI

/** 内置 HTTP server（§5）：/health /info /auth /render /message /param /state /events /theme(s) /media */
private const val MAX_BODY_BYTES = 4 * 1024 * 1024
private val PUBLIC_PATHS = setOf("/health", "/info", "/auth")
private val MUTATING = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
/** 上传媒体是 raw body，内容类型可以是任意 mime */
private const val PATH_MEDIA_UPLOAD = "/media"

object Server {
    const val DEFAULT_PORT = 43210
    private const val PORT_FALLBACK_TRIES = 10

    /** 实际监听端口（端口被占时会顺延，agent 以 /info.app.port 为准） */
    @Volatile var port: Int = DEFAULT_PORT
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun isRunning(): Boolean = engine != null

    /**
     * 启动（幂等）。返回 true 表示已在监听。
     * P0-3：保留 engine 引用以便停止；端口冲突顺延而不是直接崩。
     */
    fun start(): Boolean {
        if (engine != null) return true
        for (i in 0..PORT_FALLBACK_TRIES) {
            val p = DEFAULT_PORT + i
            try {
                val e = embeddedServer(CIO, port = p, host = "0.0.0.0") { module() }
                e.start(wait = false)
                engine = e
                port = p
                lastError = null
                return true
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                android.util.Log.w("aicast", "server start failed on $p", t)
            }
        }
        return false
    }

    fun stop() {
        try { engine?.stop(300, 1000) } catch (t: Throwable) { /* ignore */ }
        engine = null
    }

    private fun Application.module() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        intercept(ApplicationCallPipeline.Plugins) { if (!guard(call)) finish() }
        routing { mount(this) }
    }
}

// ---------------- 统一门禁（CSRF → 写操作内容类型 → token） ----------------

/** 返回 true = 放行；false = 已自行响应，调用方需 finish() */
private suspend fun guard(call: ApplicationCall): Boolean {
    // ① 跨站拦截：浏览器发起的跨源请求一定带 Origin（P1-12）
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null && !allowedOrigin(origin, call.request.host())) {
        call.respond(
            HttpStatusCode.Forbidden,
            err("cross_origin_denied", Str.get(R.string.err_cross_origin))
        )
        return false
    }
    if (call.request.httpMethod == HttpMethod.Options) {
        corsHeaders(call, origin)
        call.respond(HttpStatusCode.NoContent)
        return false
    }
    // ② 写操作必须带 Content-Length + application/json：
    //    浏览器 simple request 既不能伪造 application/json，也不能加自定义头 → CSRF 投屏被挡
    //    （只有 POST /media 是 raw body 例外，其它写操作一律要求 JSON——含 DELETE /media）
    val isRawUpload = call.request.httpMethod == HttpMethod.Post && call.request.path() == PATH_MEDIA_UPLOAD
    if (call.request.httpMethod in MUTATING && !isRawUpload) {
        val ct = call.request.contentType().toString()
        if (!ct.startsWith("application/json", ignoreCase = true)) {
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                err("content_type_required", Str.get(R.string.err_content_type))
            )
            return false
        }
    }
    // ③ token 门禁（§4/§5）：/health /info /auth 公开；媒体读取公开（WebView 没法带 token）
    val p = call.request.path()
    val mediaGet = p.startsWith("/media/") && call.request.httpMethod == HttpMethod.Get
    val gateOpen = mediaGet || p in PUBLIC_PATHS || Prefs.mode != "auth" || !Prefs.hasPassword()
    if (!gateOpen) {
        // token 只走 Authorization 头：不再接受 ?token=（会进各种日志）
        val tok = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")?.trim()?.ifEmpty { null }
        if (tok == null || !Auth.valid(tok)) {
            call.respond(
                HttpStatusCode.Unauthorized,
                err("unauthorized", Str.get(R.string.err_unauthorized))
            )
            return false
        }
    }
    if (mediaGet) corsHeaders(call, null)
    return true
}

private fun allowedOrigin(origin: String, host: String): Boolean =
    origin.equals("https://aicast.local", ignoreCase = true) ||
        origin.equals("http://aicast.local", ignoreCase = true) ||
        origin.equals("http://$host", ignoreCase = true)

private fun corsHeaders(call: ApplicationCall, origin: String?) {
    // GET /media/{id} 回填需要被页面 fetch/XHR 直接读到；其余不开放跨域
    call.response.header(HttpHeaders.AccessControlAllowOrigin, origin ?: "*")
    call.response.header("Access-Control-Allow-Headers", "Authorization, Content-Type")
    call.response.header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
}

// ---------------- 路由 ----------------

private fun mount(rt: Routing) = with(rt) {

    get("/health") { call.respondText("ok") }

    get("/info") { call.respond(DeviceInfo.json()) }

    post("/auth") {
        if (Prefs.mode != "auth" || !Prefs.hasPassword())
            return@post call.respond(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("type", JsonPrimitive("public"))
                    put("message", JsonPrimitive(Str.get(R.string.auth_public_msg)))
                }
            )
        if (Auth.locked())
            return@post call.respond(
                HttpStatusCode.TooManyRequests,
                errLocked(Str.get(R.string.auth_locked_msg), Auth.lockRemainingMs())
            )
        val body = try { call.receiveLimited() }
        catch (e: BadBody) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
        }
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, err("payload_too_large", Str.get(R.string.err_payload_too_large)))
        val pwd = parseObj(body)?.str("password")
            ?: return@post call.respond(
                HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_password))
            )
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
                if (nowLocked) errLocked(Str.get(R.string.auth_wrong_password), Auth.lockRemainingMs())
                else err("wrong_password", Str.get(R.string.auth_wrong_password))
            )
        }
    }

    post("/render") {
        // 投送总开关关着：设备仍可被发现，但投送一律拒绝，并明确告诉 agent 该让人去哪开
        if (!Prefs.isOpen("render"))
            return@post call.respond(
                HttpStatusCode.Forbidden,
                err("cast_disabled", castDisabledMsg())
            )
        val body = try { call.receiveLimited() }
        catch (e: BadBody) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
        }
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, err("payload_too_large", Str.get(R.string.err_payload_too_large)))
        val obj = parseObj(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", Str.get(R.string.err_bad_json_object)))
        val html = obj.str("html")
        val url = obj.str("url")
        val via = call.request.local.remoteHost
        when {
            html != null -> {
                // P0-4：界面不在前台时**不再假装成功**，内容排队等界面起来再投
                // `--wake` 对 HTML 投送同样生效：电视熄屏时投送，内容要能把屏幕叫亮
                val delivered = CommandBus.renderHtml(html, obj.bool("wake"))
                History.add(html, via)
                if (delivered) call.respond(ok("render"))
                else call.respond(
                    HttpStatusCode.Accepted,
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("type", JsonPrimitive("render"))
                        put("queued", JsonPrimitive(true))
                        put("message", JsonPrimitive(Str.get(R.string.render_queued_msg)))
                        put("ts", JsonPrimitive(System.currentTimeMillis()))
                    }
                )
            }
            url != null -> {
                // P1-9：只允许 http/https，挡掉 javascript:/file:/content: 等
                val scheme = runCatching { URI(url).scheme }.getOrNull()
                if (scheme == null || !scheme.equals("http", true) && !scheme.equals("https", true))
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        err("bad_url", Str.get(R.string.err_bad_url))
                    )
                if (!CommandBus.loadUrl(url, obj.bool("wake")))
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        err("no_display", Str.get(R.string.err_no_display_url))
                    )
                call.respond(ok("render"))
            }
            else -> { CommandBus.clear(); call.respond(ok("idle")) } // 空内容 = 回待机
        }
    }

    get("/history") { call.respondText(History.listJson(), contentType = ContentType.Application.Json) }

    // 主题列表 + 当前项（供 agent 发现可切换/已注册的首页主题）
    get("/themes") { call.respondText(Themes.listJson(), contentType = ContentType.Application.Json) }

    // 注册一个 agent 自定义 HTML 首页主题；activate=true 立即设为首页
    post("/theme") {
        if (!hasPerm(call, "render", Str.get(R.string.perm_hint_register_theme))) return@post
        val body = try { call.receiveLimited() }
        catch (e: BadBody) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
        }
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, err("payload_too_large", Str.get(R.string.err_payload_too_large)))
        val obj = parseObj(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", Str.get(R.string.err_bad_json_object)))
        val html = obj.str("html")
            ?: return@post call.respond(
                HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_html))
            )
        val meta = try {
            Themes.register(html, obj.str("name"), obj.str("id"))
        } catch (e: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_theme", e.message ?: ""))
        } catch (e: Exception) {
            return@post call.respond(HttpStatusCode.InternalServerError, err("theme_failed", e.message ?: ""))
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

    // P0-6：删除此前是零权限校验，公开模式下任何人都能清空别人的主题
    delete("/theme") {
        if (!hasPerm(call, "render", Str.get(R.string.perm_hint_delete_theme))) return@delete
        val id = call.request.queryParameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_id)))
        val removed = Themes.remove(id)
        call.respond(buildJsonObject {
            put("ok", JsonPrimitive(removed))
            put("type", JsonPrimitive("theme"))
            put("removed", JsonPrimitive(id))
        })
    }

    // ---------- 媒体（§5 /media）：局域网媒体兜底，见 Media.kt 注释 ----------

    // 上传：raw body 流式落盘（需 Content-Length），?name= 提供原名；复用 render 权限
    post("/media") {
        if (!hasPerm(call, "render", Str.get(R.string.perm_hint_upload_media))) return@post
        val len = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            ?: return@post call.respond(
                HttpStatusCode.LengthRequired,
                err("length_required", Str.get(R.string.media_need_length))
            )
        if (len <= 0) return@post call.respond(
            HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.media_empty_upload))
        )
        val maxSingle = Media.effectiveMaxSingle()
        if (len > maxSingle)
            return@post call.respond(
                HttpStatusCode.PayloadTooLarge,
                err("payload_too_large", Str.get(R.string.media_limit_mb, maxSingle / 1024 / 1024))
            )
        if (!Media.hasRoom(len))
            return@post call.respond(
                HttpStatusCode.InsufficientStorage,
                err("no_space", Str.get(R.string.media_no_space))
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
            if (copied > maxSingle) {
                tmp.delete()
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    err("payload_too_large", Str.get(R.string.media_over_limit))
                )
            }
            if (copied != len) {
                tmp.delete()
                return@post call.respond(
                    HttpStatusCode.BadRequest, err("incomplete", Str.get(R.string.media_length_mismatch))
                )
            }
            val meta = Media.commit(name, type, tmp)
            CommandBus.emit(
                "media.add",
                buildJsonObject {
                    put("id", JsonPrimitive(meta.id))
                    put("name", JsonPrimitive(meta.name))
                    put("size", JsonPrimitive(meta.size))
                }.toString()
            )
            call.respond(buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("type", JsonPrimitive("media"))
                put("id", JsonPrimitive(meta.id))
                put("url", JsonPrimitive("/media/${meta.id}"))
                put("size", JsonPrimitive(meta.size))
                put("ts", JsonPrimitive(System.currentTimeMillis()))
            })
        } catch (e: IllegalArgumentException) {
            tmp.delete()
            call.respond(
                HttpStatusCode.BadRequest,
                err("bad_media", e.message ?: Str.get(R.string.media_bad_param))
            )
        } catch (e: IllegalStateException) {
            tmp.delete()
            call.respond(
                HttpStatusCode.InsufficientStorage,
                err("no_space", e.message ?: Str.get(R.string.media_no_storage))
            )
        } catch (e: Exception) {
            tmp.delete()
            call.respond(
                HttpStatusCode.InternalServerError,
                err("media_failed", e.message ?: Str.get(R.string.media_write_failed))
            )
        }
    }

    get("/media") {
        call.respondText(Media.listJson(), contentType = ContentType.Application.Json)
    }

    // P0-6：同上，删除此前零校验
    delete("/media") {
        if (!hasPerm(call, "render", Str.get(R.string.perm_hint_delete_media))) return@delete
        val id = call.request.queryParameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_id)))
        val removed = Media.remove(id)
        call.respond(buildJsonObject {
            put("ok", JsonPrimitive(removed))
            put("type", JsonPrimitive("media"))
            put("removed", JsonPrimitive(id))
        })
    }

    // 拉流：手动实现 Range（2.3.12 无 PartialContent 构件）——音频边下边播 + 拖动 seek 的关键
    get("/media/{id}") {
        val id = java.net.URLDecoder.decode(
            call.request.path().substringAfterLast('/'), "UTF-8"
        )
        val meta = Media.get(id)
            ?: return@get call.respond(
                HttpStatusCode.NotFound, err("not_found", Str.get(R.string.media_not_found, id))
            )
        val f = Media.fileOf(meta)
            ?: return@get call.respond(
                HttpStatusCode.NotFound, err("gone", Str.get(R.string.media_gone))
            )
        val ctype = try {
            ContentType.parse(meta.type)
        } catch (e: Exception) {
            ContentType.Application.OctetStream
        }
        val total = f.length()
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val rm = call.request.headers[HttpHeaders.Range]?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (rm == null) {
            call.respond(LocalFileContent(f, contentType = ctype))
            return@get
        }
        var start = rm.groupValues[1].toLongOrNull()
        var end = rm.groupValues[2].toLongOrNull()
        if (start == null && end != null) { start = (total - end).coerceAtLeast(0); end = null } // 后缀区间 bytes=-N
        val s = start ?: 0L
        val e2 = (end ?: (total - 1)).coerceAtMost(total - 1)
        if (s > e2 || s >= total) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$total")
            return@get call.respond(
                HttpStatusCode.RequestedRangeNotSatisfiable,
                err("bad_range", Str.get(R.string.media_bad_range))
            )
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
                HttpStatusCode.Forbidden, err("permission_denied", Str.get(R.string.err_perm_denied_message))
            )
        val body = try { call.receiveLimited() }
        catch (e: BadBody) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
        }
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, err("payload_too_large", Str.get(R.string.err_payload_too_large)))
        parseObjOrJson(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", Str.get(R.string.err_bad_json)))
        if (!CommandBus.deliverMessage(body))
            return@post call.respond(
                HttpStatusCode.Conflict,
                err("no_display", Str.get(R.string.err_msg_no_page))
            )
        call.respond(ok("message"))
    }

    get("/state") { call.respond(CommandBus.stateJson()) }

    post("/param") {
        if (!Prefs.isOpen("param"))
            return@post call.respond(
                HttpStatusCode.Forbidden, err("permission_denied", Str.get(R.string.err_perm_denied_param))
            )
        val obj = try { parseObj(call.receiveLimited() ?: "") }
        catch (e: BadBody) {
            return@post call.respond(HttpStatusCode.BadRequest, err("bad_encoding", e.message ?: ""))
        }
            ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_json", Str.get(R.string.err_bad_json_object)))
        val k = obj.str("key")
            ?: return@post call.respond(HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_key)))
        val v = obj.str("value")
            ?: return@post call.respond(
                HttpStatusCode.BadRequest, err("bad_request", Str.get(R.string.err_need_value))
            )
        if (Params.set(k, v)) call.respond(ok("param"))
        else if (k == "theme") call.respond(
            HttpStatusCode.BadRequest,
            err(
                "unknown_theme",
                Str.get(R.string.err_unknown_theme, v, Themes.list().joinToString("/") { it.id })
            )
        )
        else call.respond(
            HttpStatusCode.BadRequest,
            err(
                "unknown_param",
                Str.get(R.string.err_unknown_param, k, Params.supportedKeys().joinToString("/"))
            )
        )
    }

    get("/param") {
        call.respond(buildJsonObject {
            Params.supportedKeys().forEach { put(it, JsonPrimitive(Params.get(it))) }
        })
    }

    /**
     * long-poll：有事件立即返回，否则挂到 timeout（默认 25s）。
     * 返回体统一为对象：`{ok,lastSeq,reset,events:[…]}`。
     * `reset=true` 表示设备的事件序号已回退（如缓冲被重置），调用方应把 since 归零重来（P0-2）。
     */
    get("/events") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val timeout = (call.request.queryParameters["timeout"]?.toLongOrNull() ?: 25L)
            .coerceIn(0L, 60L)
        val last = CommandBus.lastSeq()
        val reset = since > last
        if (CommandBus.eventsSince(since).isEmpty() && timeout > 0) {
            // 由 emit 唤醒，最多 500ms 的等待粒度，而不是固定轮询一整轮
            CommandBus.awaitChange(timeout * 1000)
        }
        call.respond(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("lastSeq", JsonPrimitive(CommandBus.lastSeq()))
            put("reset", JsonPrimitive(reset))
            put("events", CommandBus.eventsJson(if (reset) 0L else since))
        })
    }
}

// ---------------- helpers ----------------

/**
 * 投送总开关关着时给 agent 的统一说法（/info.acceptingHint 用的是同一句）。
 * 随设备语言走：这句话的设计目的就是「agent 可以原样转述给用户」。
 */
internal fun castDisabledMsg(): String = Str.get(
    R.string.hint_cast_disabled,
    Str.get(R.string.app_name),
    Str.get(R.string.settings_access_mode),
    Str.get(R.string.settings_cast_title)
)

/** 逐项权限判定：无权限时直接写回 403 并返回 false，调用方 return 即可 */
private suspend fun hasPerm(call: ApplicationCall, permission: String, hint: String): Boolean {
    if (Prefs.isOpen(permission)) return true
    // render 现在就是投送总开关，措辞要能直接转述给人看
    val msg = if (permission == "render") castDisabledMsg()
    else Str.get(R.string.err_perm_denied_generic, permission, hint)
    call.respond(HttpStatusCode.Forbidden, err("permission_denied", msg))
    return false
}

private suspend fun ApplicationCall.receiveLimited(): String? {
    val len = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (len != null && len > MAX_BODY_BYTES) return null
    return try {
        receiveText()
    } catch (e: io.ktor.utils.io.charsets.MalformedInputException) {
        throw BadBody(Str.get(R.string.err_bad_encoding))
    }
}

/** 客户端报文级错误 → 400 */
private class BadBody(msg: String) : Exception(msg)

private fun parseObj(s: String): JsonObject? =
    try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }

/** message 允许任意 JSON（不只对象），只要合法即可 */
private fun parseObjOrJson(s: String): JsonElement? =
    try { Json.parseToJsonElement(s) } catch (e: Exception) { null }

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

private fun errLocked(msg: String, remainingMs: Long) = buildJsonObject {
    put("ok", JsonPrimitive(false))
    put("code", JsonPrimitive("locked"))
    put("message", JsonPrimitive(Str.get(R.string.err_locked, msg, (remainingMs + 999) / 1000)))
    put("retryAfterMs", JsonPrimitive(remainingMs))
}
