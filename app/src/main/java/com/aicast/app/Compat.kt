package com.aicast.app

/**
 * agent 端技能（仓库 `skill/` 目录）与 app 的兼容基线。
 *
 * 为什么要这个东西：技能装在用户的电脑上，app 升级时没有任何办法去更新它
 * （跨机器、跨产品、无凭证），所以线上必然长期存在「新 app + 老技能」的组合。
 * 唯一的办法是让老技能自己发现落后，并且知道该跑什么命令升级自己。
 *
 * 契约（只增不改，见 DESIGN.md §5）：
 *   `GET /info` → `app.minSkillVersion`：能完整驱使本版本 app 的**最低**技能版本。
 *
 * 版本号语义（semver，只比 MAJOR.MINOR.PATCH 三段）：
 *   - 技能新增用法、app 新增可用能力：MIN 不动（老技能照旧能用）
 *   - app 改了既有字段语义 / 删端点 / 让老技能必然出错：MIN 升到该技能版本
 *
 * 改这里的铁律：**必须同步** `skill/SKILL.md` 的 `version:` 与 README 的升级说明，
 * 否则会出现「app 要求 1.2.0，而技能仓库里最高只有 1.1.0」的死锁。
 */
object Compat {
    /** 当前 app 要求的最低技能版本 */
    const val MIN_SKILL_VERSION = "1.1.0"

    /**
     * 比较两个 `MAJOR.MINOR.PATCH` 版本串，返回 a<b 时 true。
     * 非 semver 串（含前后缀、空串）一律视为「不落后」——宁可少提示，
     * 也不能因为解析不了就把所有用户都判成需要升级。
     */
    fun versionLessThan(a: String?, b: String?): Boolean {
        val pa = parse(a) ?: return false
        val pb = parse(b) ?: return false
        for (i in 0..2) {
            val d = pa[i].compareTo(pb[i])
            if (d != 0) return d < 0
        }
        return false
    }

    private fun parse(v: String?): IntArray? {
        if (v.isNullOrBlank()) return null
        val core = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size != 3) return null
        val nums = IntArray(3)
        for (i in 0..2) nums[i] = parts[i].toIntOrNull() ?: return null
        return nums
    }
}
