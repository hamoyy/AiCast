package com.aiface.app

import android.content.Context
import java.security.SecureRandom

/**
 * token 会话（§4 auth 模式）：密码 → 长期 token（30 天），支持多 agent 各自持有；
 * 防爆破：连错 5 次锁 60 秒；可在设置页全部吊销。
 */
object Auth {

    private const val TTL = 30L * 24 * 3600 * 1000
    private const val MAX_WRONG = 5
    private const val LOCK_MS = 60_000L

    private val sp get() = App.ctx.getSharedPreferences("aiface_auth", Context.MODE_PRIVATE)

    @Volatile private var wrong = 0
    @Volatile private var lockUntil = 0L
    @Volatile private var cache: Map<String, Long>? = null

    private fun tokens(): MutableMap<String, Long> {
        cache?.let { return it.toMutableMap() }
        val raw = sp.getString("tokens", "") ?: ""
        val m = raw.split(";").mapNotNull {
            val p = it.split(":")
            if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
        }.toMap().toMutableMap()
        cache = m
        return m
    }

    private fun save(m: Map<String, Long>) {
        cache = m
        sp.edit().putString("tokens", m.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }

    fun locked(): Boolean = System.currentTimeMillis() < lockUntil

    fun noteWrongPassword(): Boolean {
        wrong++
        if (wrong >= MAX_WRONG) { lockUntil = System.currentTimeMillis() + LOCK_MS; wrong = 0; return true }
        return false
    }

    fun noteSuccess() { wrong = 0; lockUntil = 0L }

    /** 密码换 token：同一密码重复申请返回新 token（旧 token 仍有效直到过期/吊销） */
    fun issue(): Pair<String, Long> {
        val now = System.currentTimeMillis()
        val m = tokens().filterValues { it > now }.toMutableMap()
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        m[t] = now + TTL
        save(m)
        return t to now + TTL
    }

    fun valid(token: String): Boolean = (tokens()[token] ?: 0L) > System.currentTimeMillis()

    fun activeCount(): Int = tokens().values.count { it > System.currentTimeMillis() }

    fun revokeAll() { save(emptyMap()) }
}
