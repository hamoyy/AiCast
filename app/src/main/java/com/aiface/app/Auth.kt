package com.aiface.app

import android.content.Context
import java.security.SecureRandom

/**
 * token 会话（§4 auth 模式）：密码 → 长期 token（30 天），支持多 agent 各自持有；
 * 防爆破：连错 5 次锁 60 秒，计数**落盘**（进程重启不会清零，P1-14）；可在设置页全部吊销。
 *
 * 存储格式 `<token>:<过期时刻>:<签发时刻>`，条目间 `;` 分隔。
 * 只有两段的旧格式（`<token>:<过期时刻>`）按「过期时刻减一个 TTL」反推签发时刻，
 * 保证升级后已登录的 agent 不会被强制下线。
 */
object Auth {

    private const val TTL = 30L * 24 * 3600 * 1000
    private const val MAX_WRONG = 5
    private const val LOCK_MS = 60_000L
    private const val WRONG_WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_ACTIVE_TOKENS = 32

    private fun sp() = App.ctx.getSharedPreferences("aiface_auth", Context.MODE_PRIVATE)

    /** @property exp 过期时刻 @property iat 签发时刻 */
    private data class Session(val exp: Long, val iat: Long)

    @Volatile private var cache: Map<String, Session>? = null

    private fun tokens(): MutableMap<String, Session> {
        cache?.let { return it.toMutableMap() }
        val raw = sp().getString("tokens", "") ?: ""
        val m = raw.split(";").mapNotNull {
            val p = it.split(":")
            val exp = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            p[0] to when (p.size) {
                2 -> Session(exp, exp - TTL) // 旧格式：反推签发时刻
                else -> Session(exp, p.getOrNull(2)?.toLongOrNull() ?: (exp - TTL))
            }
        }.toMap().toMutableMap()
        cache = m
        return m
    }

    private fun save(m: Map<String, Session>) {
        cache = m
        sp().edit()
            .putString("tokens", m.entries.joinToString(";") { "${it.key}:${it.value.exp}:${it.value.iat}" })
            .apply()
    }

    fun locked(): Boolean = System.currentTimeMillis() < sp().getLong("lockUntil", 0L)

    fun lockRemainingMs(): Long = (sp().getLong("lockUntil", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 返回 true 表示本次触发锁定 */
    fun noteWrongPassword(): Boolean {
        val now = System.currentTimeMillis()
        val p = sp().edit()
        val since = sp().getLong("wrongSince", 0L)
        var wrong = sp().getInt("wrong", 0)
        if (now - since > WRONG_WINDOW_MS) {
            wrong = 0
            p.putLong("wrongSince", now)
        }
        wrong++
        return if (wrong >= MAX_WRONG) {
            p.putInt("wrong", 0).putLong("lockUntil", now + LOCK_MS).apply()
            true
        } else {
            p.putInt("wrong", wrong).apply()
            false
        }
    }

    fun noteSuccess() { sp().edit().putInt("wrong", 0).putLong("lockUntil", 0L).apply() }

    /** 密码换 token：同一密码重复申请返回新 token（旧 token 仍有效直到过期/吊销） */
    fun issue(): Pair<String, Long> {
        val now = System.currentTimeMillis()
        val m = tokens().filterValues { it.exp > now }.toMutableMap()
        // 活跃 token 封顶 **32**（P1-14 防无上限增长）。淘汰顺序是这里的关键（P1-15）：
        // 旧实现丢「最快过期」的，等于丢「最早签发」的——反复调用 /auth 的脚本
        // （测试脚本、写坏了的重连逻辑）会把长期在线的 agent 一个个挤下线，
        // 而它自己的 token 因为一直最新反而永远活着。
        // 改成丢「最新签发」的后，刷接口的客户端每次新签发都淘汰它自己上一个，
        // 最多只占 1 个名额，存量会话不受影响。
        if (m.size >= MAX_ACTIVE_TOKENS) {
            m.entries.sortedByDescending { it.value.iat }
                .take(m.size - MAX_ACTIVE_TOKENS + 1)
                .map { it.key }
                .forEach { m.remove(it) }
        }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        m[t] = Session(now + TTL, now)
        save(m)
        return t to now + TTL
    }

    fun valid(token: String): Boolean = (tokens()[token]?.exp ?: 0L) > System.currentTimeMillis()

    fun activeCount(): Int = tokens().values.count { it.exp > System.currentTimeMillis() }

    fun revokeAll() { save(emptyMap()) }
}
