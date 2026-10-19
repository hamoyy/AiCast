package com.aicast.app

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 设备画像采集与上报（§7）。只采集上报，不做任何按设备类型的差异化逻辑。 */
object DeviceInfo {

    private val ctx get() = App.ctx

    fun deviceClass(): String {
        val cfg = ctx.resources.configuration
        val uiModeType = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK
        return when {
            uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || isLeanback() -> "tv"
            uiModeType == Configuration.UI_MODE_TYPE_WATCH -> "wearable"
            cfg.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
    }

    fun isTouch(): Boolean =
        hasFeature(PackageManager.FEATURE_TOUCHSCREEN) || hasFeature("android.hardware.faketouch")

    fun isDpad(): Boolean = isLeanback() || hasFeature("android.hardware.gamepad")

    fun json(): JsonElement {
        val dm = metrics()
        val cfg = ctx.resources.configuration
        val orientation = when (cfg.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "portrait"
        }
        val klass = deviceClass()
        val touch = isTouch()
        val dpad = isDpad()
        val interaction = when {
            touch -> "touch"
            dpad -> "remote"
            else -> "auto"
        }
        val density = (dm.density.takeIf { it > 0f } ?: 1f).toDouble()
        val diagInch =
            hypot(dm.widthPixels.toDouble(), dm.heightPixels.toDouble()) / dm.densityDpi
        val rotationLock = try {
            if (Build.VERSION.SDK_INT >= 18)
                Settings.System.getInt(
                    ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
                ) == 0
            else false
        } catch (e: Exception) { false }

        val batt = battery()
        val accepting = Prefs.castEnabled

        return JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(Prefs.deviceId),
                "name" to JsonPrimitive(Prefs.name),
                "mode" to JsonPrimitive(Prefs.mode),
                "authRequired" to JsonPrimitive(Prefs.mode == "auth" && Prefs.hasPassword()),
                // 投送总开关：关掉时依然可被发现、依然上报画像，但必须显式声明"不可被投送"。
                // 附一句可直接转述给人看的话，省得 agent 自己措辞。
                "accepting" to JsonPrimitive(accepting),
                *if (accepting) emptyArray() else arrayOf(
                    "acceptingHint" to JsonPrimitive(castDisabledMsg())
                ),
                "open" to JsonArray(Prefs.openList().map { JsonPrimitive(it) }),
                "device" to JsonObject(
                    mapOf(
                        "class" to JsonPrimitive(klass),
                        "manufacturer" to JsonPrimitive(Build.MANUFACTURER),
                        "model" to JsonPrimitive(Build.MODEL),
                        "osVersion" to JsonPrimitive(Build.VERSION.RELEASE),
                        "input" to JsonObject(
                            mapOf(
                                "touch" to JsonPrimitive(touch),
                                "dpad" to JsonPrimitive(dpad),
                                "keyboard" to JsonPrimitive(
                                    hasFeature("android.hardware.keyboard")
                                ),
                                "pointer" to JsonPrimitive(hasFeature("android.hardware.mouse")),
                                "stylus" to JsonPrimitive(false)
                            )
                        ),
                        "interactionModel" to JsonPrimitive(interaction),
                        "largeScreen" to JsonPrimitive(diagInch >= 20.0 || cfg.smallestScreenWidthDp >= 720),
                        "diagonalInches" to JsonPrimitive(round1(diagInch)),
                        "recommendedFontScale" to JsonPrimitive(if (diagInch >= 40) 1.8 else if (diagInch >= 20) 1.4 else 1.0),
                        // 投送内容能用哪些 CSS/JS 由内核版本决定，与 osVersion/型号无关（见 webViewJson 注释）
                        "webview" to webViewJson()
                    )
                ),
                "screen" to JsonObject(
                    mapOf(
                        "widthPx" to JsonPrimitive(dm.widthPixels),
                        "heightPx" to JsonPrimitive(dm.heightPixels),
                        "widthDp" to JsonPrimitive((dm.widthPixels / density).toInt()),
                        "heightDp" to JsonPrimitive((dm.heightPixels / density).toInt()),
                        "density" to JsonPrimitive(density.toDouble()),
                        "densityDpi" to JsonPrimitive(dm.densityDpi),
                        "orientation" to JsonPrimitive(orientation),
                        "orientationLocked" to JsonPrimitive(rotationLock),
                        "refreshRate" to JsonPrimitive(refreshRate()),
                        "fontScale" to JsonPrimitive(cfg.fontScale),
                        // 刘海/挖孔安全区（dp）。页面拿不到它——见 cutoutInsets 注释
                        "cutoutInsets" to cutoutInsets(density)
                    )
                ),
                "appearance" to JsonObject(
                    mapOf("darkMode" to JsonPrimitive(isDarkMode()))
                ),
                "internet" to internetJson(),
                "system" to JsonObject(
                    mapOf(
                        "android" to JsonPrimitive(Build.VERSION.SDK_INT.toString()),
                        "battery" to (batt?.let { JsonPrimitive(it) } ?: JsonNull),
                        "charging" to JsonPrimitive(isCharging()),
                        "screenOn" to JsonPrimitive(isScreenOn())
                    )
                ),
                "capabilities" to JsonArray(capabilities().map { JsonPrimitive(it) }),
                "app" to JsonObject(
                    mapOf(
                        "version" to JsonPrimitive(appVersion()),
                        "port" to JsonPrimitive(Server.port),
                        // 界面是否真的可见：false 时 /render 一律 202 排队，内容不会"投上去看不见"
                        "foreground" to JsonPrimitive(App.foreground)
                    )
                )
            )
        )
    }

    /**
     * **真实可用**的能力清单 = 已实现的端点 × 逐项权限开关。
     * 以前把 tts/notify/clipboard/launchApp 等没实现的都写死上报，
     * agent 照着画像去调必然 404（P1-16）。
     */
    fun capabilities(): List<String> {
        val list = mutableListOf<String>()
        if (Prefs.isOpen("render")) list += listOf("render", "themes", "media")
        if (Prefs.isOpen("message")) list += "message"
        if (Prefs.isOpen("param")) list += listOf("param", "theme", "volume", "brightness", "fontScale")
        list += listOf("state", "events", "history")   // 只读能力
        // 以前这里还会追加 "eval"，但服务端并没有 /eval 端点，agent 照着画像去调
        // 只能拿到 404（P1-16 的翻版）。eval 连同 /screen /system /screenshot 的空开关
        // 已整体下线；等真端点落地再放开。
        return list.distinct()
    }

    // ---------------- WebView 内核 ----------------

    /** 检测成功过的结果缓存。取 UA 会初始化 WebView，不能每次 /info 现算。 */
    @Volatile
    private var wvCache: JsonElement? = null

    /**
     * 投送内容能用哪些 CSS / JS，取决于 **WebView 内核版本**，而不是 `osVersion` 或型号 ——
     * WebView 是独立可升级的 APK（`com.google.android.webview` / `com.android.chrome` /
     * `com.android.webview`），走各自通道；厂商电视固件基本冻结版本。实测三台：Android 13
     * 的手机是 116、Android 7.1 的手机是 65、Android TV 是 66。
     *
     * 不上报它，agent 就只能拿一台设备的基线去覆盖全机队，代价两头都吃：新机白放弃现代
     * CSS，旧机踩 `inset`/`clamp()`/`gap` 被静默丢弃。更糟的是 JS 语法：旧内核不认 `?.`
     * 或 `??` 时整段 `<script>` 直接解析失败，页面交互全丢且不报错。
     *
     * 两段式取法（API 26 以下没有 `getCurrentWebViewPackage`，魅族 7.1.2 实测返回 null）：
     * ① `WebView.getCurrentWebViewPackage()` —— API 26+，拿到的 `versionName` 就是
     *    `116.0.5845.92` 这种完整版本号，且**不会初始化 WebView**；
     * ② 回退 `WebSettings.getDefaultUserAgent()`，从 UA 里抠 `Chrome/(\d+)` —— UA 形如
     *    `… Version/4.0 Chrome/65.0.3325.110 Mobile Safari/537.36`，版本号干净可用。
     */
    private fun webViewJson(): JsonElement {
        wvCache?.let { return it }
        val j = detectWebView()
        // 只在真拿到版本号时缓存：WebView 尚未初始化时可能取空，缓存下来就永远是 unknown 了
        val major = ((j as JsonObject)["major"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (major > 0) wvCache = j
        return j
    }

    private fun detectWebView(): JsonElement {
        var pkg: String? = null
        var version: String? = null
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val p = WebView.getCurrentWebViewPackage()
                pkg = p?.packageName
                version = p?.versionName
            } catch (e: Exception) { /* 取不到就落到 UA 回退 */ }
        }
        if (version.isNullOrBlank()) {
            try {
                val ua = WebSettings.getDefaultUserAgent(ctx)
                version = Regex("Chrome/([0-9]+(?:\\.[0-9]+)*)").find(ua)?.groupValues?.get(1)
            } catch (e: Exception) { /* 非主线程初始化失败等，报 unknown 即可，别影响 /info */ }
        }
        val major = version?.substringBefore('.')?.toIntOrNull() ?: 0
        return JsonObject(
            mapOf(
                "engine" to JsonPrimitive(if (version.isNullOrBlank()) "unknown" else "chromium"),
                "version" to JsonPrimitive(version ?: ""),
                // major 是 agent 唯一需要比对的量：CSS/JS 能力全按它分档
                "major" to JsonPrimitive(major),
                // 低版本（无 getCurrentWebViewPackage）拿不到包名，此时为 null
                "package" to (pkg?.let { JsonPrimitive(it) } ?: JsonNull)
            )
        )
    }

    // ---------------- 挖孔安全区 ----------------

    /**
     * 刘海/挖孔安全区，单位 **dp**（与页面 CSS px 同量级，页面可直接拿去当 `padding`）。
     *
     * 为什么必须由画像上报：本 app 的 WebView 是沉浸式全屏，实测 `viewport-fit=cover` 下
     * `env(safe-area-inset-top)` **恒为 `0px`**（带挖孔的 Mi 10 上，页面里读它仍是 0），
     * 所以页面根本拿不到这个值。旧内核更直接——Chromium 65/66 连 `env()` 都不支持，
     * 整条声明被丢弃。
     *
     * Android 9（API 28）以下没有挖孔概念，四项恒为 0；这类设备无需避让。
     */
    private fun cutoutInsets(density: Double): JsonElement {
        var top = 0; var bottom = 0; var left = 0; var right = 0
        val ins = displayCutoutInsets()
        if (ins != null) {
            left = ins.left; top = ins.top; right = ins.right; bottom = ins.bottom
        }
        fun dp(px: Int) = Math.round(px / density).toInt()
        return JsonObject(
            mapOf(
                "top" to JsonPrimitive(dp(top)),
                "bottom" to JsonPrimitive(dp(bottom)),
                "left" to JsonPrimitive(dp(left)),
                "right" to JsonPrimitive(dp(right))
            )
        )
    }

    /**
     * 取窗口的挖孔 insets（物理像素）。两条路：
     * ① API 30+：`WindowManager.currentWindowMetrics` 直接给，**不依赖 Activity**；
     * ② API 28~29：只能从 Activity 的窗口 insets 里读（`DisplayCutout.getSafeInsetTop()`
     *    是实例方法，没有带 displayId 的公开静态版本，拿不到 detached 的值）。
     * API 28 以下没有挖孔概念，直接 null。
     */
    private fun displayCutoutInsets(): android.graphics.Insets? {
        if (Build.VERSION.SDK_INT < 28) return null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val wm = ctx.getSystemService(WindowManager::class.java)
                return wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            }
            val cut = App.activity?.window?.decorView?.rootWindowInsets?.displayCutout
            return cut?.let {
                android.graphics.Insets.of(
                    it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom
                )
            }
        } catch (e: Exception) {
            return null   // 个别 ROM 的实现会抛，按无挖孔处理，别影响 /info
        }
    }

    private fun metrics(): DisplayMetrics {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun refreshRate(): Int = try {
        val wm = ctx.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        wm.defaultDisplay.refreshRate.roundToInt()
    } catch (e: Exception) { 60 }

    private fun isLeanback(): Boolean =
        hasFeature("android.software.leanback")

    // 外网可达性由设备端判定（系统自带的 VALIDATED 探测，零流量零轮询），随画像返回，
    // agent 生成内容前据此决定外链 / 内联 / /media 的取舍
    private fun internetJson(): JsonElement = try {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
        ) == true
        val transport = when {
            caps == null -> ""
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        JsonObject(
            mapOf(
                "state" to JsonPrimitive(
                    when {
                        caps == null -> "offline"
                        validated -> "online"
                        else -> "unknown"
                    }
                ),
                "validated" to JsonPrimitive(validated),
                "transport" to JsonPrimitive(transport)
            )
        )
    } catch (e: Exception) {
        JsonObject(mapOf("state" to JsonPrimitive("unknown")))
    }

    private fun hasFeature(f: String): Boolean =
        ctx.packageManager.hasSystemFeature(f)

    private fun battery(): Int? = try {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
    } catch (e: Exception) { null }

    private fun isCharging(): Boolean = try {
        val status = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) { false }

    private fun isScreenOn(): Boolean = try {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    } catch (e: Exception) { true }

    fun isDarkMode(): Boolean {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES ||
            AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun round1(v: Double): Double = Math.round(v * 10) / 10.0
    private fun appVersion(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }
}
