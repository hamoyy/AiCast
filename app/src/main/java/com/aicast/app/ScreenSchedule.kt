package com.aicast.app

/**
 * 夜间熄屏时段的判定。
 *
 * 单独成文件、只做纯字符串/整数运算，是为了让这段逻辑**不依赖任何 Android API**——
 * 时间窗最容易写错的地方就是跨午夜（22:30 到次日 07:00），不能简单拿两个数比大小。
 */
object ScreenSchedule {

    /** "H:mm" / "HH:mm" → 当日分钟数（0..1439）；格式非法返回 null */
    fun parseMinutes(s: String): Int? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(s.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /**
     * 现在是否处于熄屏时段。
     *
     * - 任一端为空或格式非法 → 视为**未启用**，永远 false（宁可屏幕常亮，也不要莫名熄屏）；
     * - 起止相同 → 也视为未启用（否则会变成全天熄屏）。
     */
    fun inOffWindow(nowMinutes: Int, from: String, to: String): Boolean {
        val f = parseMinutes(from) ?: return false
        val t = parseMinutes(to) ?: return false
        if (f == t) return false
        return if (f < t) {
            // 同日，如 13:00–14:00
            nowMinutes in f until t
        } else {
            // 跨午夜，如 22:00–07:00
            nowMinutes >= f || nowMinutes < t
        }
    }

    /** 设置页校验：要么两端都空（不启用），要么两端都合法且不相同 */
    fun isValid(from: String, to: String): Boolean {
        if (from.isBlank() && to.isBlank()) return true
        val f = parseMinutes(from) ?: return false
        val t = parseMinutes(to) ?: return false
        return f != t
    }
}
