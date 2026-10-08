package com.aiface.app

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

/** 配置持久化 + 逐项权限开关（§2.2，公开模式下逐项判定是否放行） */
object Prefs {
    private lateinit var sp: SharedPreferences

    val openable = listOf(
        "render", "message", "param", "screen", "system", "screenshot", "eval"
    )

    fun init(c: Context) {
        sp = c.getSharedPreferences("aiface", Context.MODE_PRIVATE)
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

    fun isOpen(permission: String): Boolean = when (permission) {
        // eval 只能由本机设置页开启，网络侧不可自提权
        "eval" -> sp.getBoolean("allowEval", false)
        else -> sp.getBoolean("allow_" + permission, permission in listOf("render", "message"))
    }

    var evalEnabled: Boolean
        get() = sp.getBoolean("allowEval", false)
        set(v) = sp.edit().putBoolean("allowEval", v).apply()

    fun openList() = openable.filter { isOpen(it) }

    fun setOpen(permission: String, open: Boolean) {
        if (permission == "eval") sp.edit().putBoolean("allowEval", open).apply()
        else sp.edit().putBoolean("allow_" + permission, open).apply()
    }

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
