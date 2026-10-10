package com.aiface.app

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
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

        return JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(Prefs.deviceId),
                "name" to JsonPrimitive(Prefs.name),
                "mode" to JsonPrimitive(Prefs.mode),
                "authRequired" to JsonPrimitive(Prefs.mode == "auth" && Prefs.hasPassword()),
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
                        "recommendedFontScale" to JsonPrimitive(if (diagInch >= 40) 1.8 else if (diagInch >= 20) 1.4 else 1.0)
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
                        "fontScale" to JsonPrimitive(cfg.fontScale)
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
                        "port" to JsonPrimitive(Server.port)
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
