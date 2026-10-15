package com.aicast.app

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/** 配置持久化 + 逐项权限开关（§2.2，公开模式下逐项判定是否放行） */
object Prefs {
    private lateinit var sp: SharedPreferences

    /**
     * 用户可在设置页拨动的开关。经决策只保留**投送内容**一项。
     *
     * 历史包袱一并清掉：
     * - `screen` / `system` / `screenshot`：从未有对应 HTTP 端点，是彻头彻尾的空开关；
     * - `eval`：端点不存在、`CommandBus.eval` 也是死代码（见 DeviceInfo.capabilities 注释）。
     */
    val openable = listOf("render")

    /**
     * 常开权限：不再提供开关，但仍如实上报给 agent —— 否则 `/info.open` 里少了
     * message/param，agent 会误以为这两项不可用。
     */
    private val alwaysOpen = listOf("message", "param")

    fun init(c: Context) {
        sp = c.getSharedPreferences("aicast", Context.MODE_PRIVATE)
    }

    val deviceId: String
        get() {
            var id = sp.getString("deviceId", null)
            if (id == null) {
                id = "dev_" + UUID.randomUUID().toString().take(8)
                sp.edit().putString("deviceId", id).apply()
            }
            return id
        }

    var name: String
        get() = sp.getString("name", null)
            ?: ("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").trim()
        set(v) = sp.edit().putString("name", v).apply()

    /** public / auth（auth 模式鉴权 M3 实现） */
    var mode: String
        get() = sp.getString("mode", "public") ?: "public"
        set(v) = sp.edit().putString("mode", v).apply()

    /** 待机首页主题 id："native"（默认 Compose 时钟）或某个 html 主题 id（见 [Themes]） */
    var theme: String
        get() = sp.getString("theme", Themes.NATIVE) ?: Themes.NATIVE
        set(v) = sp.edit().putString("theme", v).apply()

    /**
     * **投送总开关**（设置页「访问模式」第一行）。关掉后设备仍会被局域网发现、
     * 仍然上报画像，但 `/info` 会声明 `accepting:false`，`POST /render` 直接失败。
     */
    var castEnabled: Boolean
        get() = sp.getBoolean("allow_render", true)
        set(v) = sp.edit().putBoolean("allow_render", v).apply()

    /**
     * 夜间熄屏时段："HH:mm"，两端都为空 = 不启用（见 [ScreenSchedule]）。
     *
     * 时段内 app 会**松开「保持屏幕常亮」**，让系统按自己的「屏幕超时」熄屏。
     * 普通 app 无权直接关屏（goToSleep 要系统签名权限、lockNow 要设备管理员，
     * 而本项目承诺不碰设备管理员），这是唯一不需要任何额外权限的做法。
     */
    var screenOffFrom: String
        get() = sp.getString("screen_off_from", "") ?: ""
        set(v) = sp.edit().putString("screen_off_from", v.trim()).apply()

    var screenOffTo: String
        get() = sp.getString("screen_off_to", "") ?: ""
        set(v) = sp.edit().putString("screen_off_to", v.trim()).apply()

    /**
     * 逐项权限判定。只剩 `render`（即投送总开关）真正会被关掉，其余一律放行 ——
     * 关键是要**不再读**旧设备里可能存着的 `allow_message=false` 之类，否则门被
     * 永久关死而 UI 上已经没有开关能把它们改回来。
     */
    fun isOpen(permission: String): Boolean = when (permission) {
        "render" -> castEnabled
        else -> true
    }

    /** 上报给 agent 的「当前放行项」= 可拨开关的现值 + 常开项 */
    fun openList() = (openable.filter { isOpen(it) } + alwaysOpen).distinct()

    // 没有 setOpen 了：投送总开关直接改 Prefs.castEnabled，别再绕一层权限名

    // ---------- 密码（auth 模式，§4） ----------
    // 加盐 sha256 + 恒定时间比较（P1-14）。首次设置时生成随机盐；
    // 老设备上已存的旧格式（无盐 sha256）在首次成功校验后自动迁移到新格式。
    fun setPassword(plain: String) {
        Auth.revokeAll() // 换密码即吊销全部旧 token
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        sp.edit()
            .putString("pwd_salt", salt)
            .putString("pwd_hash", hash(salt, plain))
            .remove("pwd")
            .apply()
    }

    fun hasPassword(): Boolean =
        sp.getString("pwd_hash", null) != null || sp.getString("pwd", null) != null

    /**
     * 关闭「密码验证」：不要求输入旧密码，直接清掉密码存根并吊销全部 token
     * （旧 token 若还有效就等于密码没关，agent 照样能写）。
     */
    fun clearPassword() {
        Auth.revokeAll()
        sp.edit().remove("pwd").remove("pwd_hash").remove("pwd_salt").apply()
    }

    fun checkPassword(plain: String): Boolean {
        val salt = sp.getString("pwd_salt", null)
        if (salt != null) {
            val expect = sp.getString("pwd_hash", null) ?: return false
            return MessageDigest.isEqual(expect.toByteArray(), hash(salt, plain).toByteArray())
        }
        val legacy = sp.getString("pwd", null) ?: return false
        return if (MessageDigest.isEqual(legacy.toByteArray(), sha256hex(plain).toByteArray())) {
            setPassword(plain) // 校验通过 → 迁移到加盐存储
            true
        } else false
    }

    private fun hash(salt: String, plain: String): String = sha256hex("$salt:$plain")

    private fun sha256hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object Net {
    @Volatile private var cached: String? = null

    /** 只读缓存（主线程安全）；由 service 后台循环调 refresh() */
    fun localIp(): String = cached ?: refresh()

    /** 扫描网卡取首个站点局域网 IPv4（wlan0/eth0 优先）；较重，勿在主线程高频调用 */
    @Synchronized
    fun refresh(): String {
        val ip = try {
            val ifs = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
            val ranked = ifs.sortedBy {
                when (it.name) {
                    "wlan0" -> 0; "eth0" -> 1; else -> 2
                }
            }
            ranked.asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull() ?: "0.0.0.0"
        } catch (e: Exception) { "0.0.0.0" }
        cached = ip
        return ip
    }
}
