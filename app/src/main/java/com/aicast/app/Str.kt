package com.aicast.app

import androidx.annotation.StringRes

/**
 * 本地化文案访问器。
 *
 * 语言完全交给 Android 资源解析：系统语言为英语时命中 `values-en/`，其余落回 `values/`。
 * 语言在「设置 → 系统 → 语言」里改掉后进程配置随之更新，Activity 会重建，无需手动切。
 *
 * 存在的理由：server / service / 各种 object 单例拿不到 Activity，也没法用 Compose 的
 * `stringResource`，但它们产生的文案（HTTP 错误、通知、主题名）同样要跟着设备语言走。
 */
object Str {

    /**
     * 当前实际生效的语言标记（"zh" / "en"）。取自资源本身而不是 Locale，
     * 这样判断结果与文案资源永远一致 —— 设备是法语时资源落回中文，这里也返回 "zh"。
     */
    val lang: String get() = get(R.string.lang_code)

    fun isEnglish(): Boolean = lang == "en"

    fun get(@StringRes resId: Int): String {
        val c = ctxOrNull() ?: return ""
        return c.getString(resId)
    }

    fun get(@StringRes resId: Int, vararg args: Any?): String {
        val c = ctxOrNull() ?: return ""
        return c.getString(resId, *args)
    }

    private fun ctxOrNull(): android.content.Context? =
        if (App.isReady()) App.ctx else null
}
