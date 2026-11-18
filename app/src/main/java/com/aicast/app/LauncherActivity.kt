package com.aicast.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 启动分派。
 *
 * - API 19~20（Android 4.4/4.4W）：没有 Compose 可用，进 [LegacyActivity]（纯 View 界面）。
 * - API 21+：进 [MainActivity]，界面与行为完全不变。
 *
 * 这里**故意用类名字符串**而不是 `MainActivity::class.java`：后者是一个 const-class 引用，
 * 在 4.4 上会触发 MainActivity 的类加载与校验，而它引用的 Compose 类体系在 API 19 上
 * 并不保证能通过校验。用字符串则只有真正要启动时才由系统去解析类。
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (android.os.Build.VERSION.SDK_INT >= 21) {
            "com.aicast.app.MainActivity"
        } else {
            "com.aicast.app.LegacyActivity"
        }
        runCatching {
            startActivity(
                Intent().setClassName(this, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
        finish()
    }

    override fun finish() {
        super.finish()
        // 分派页不该在启动时闪一下
        @Suppress("DEPRECATION")
        runCatching { overridePendingTransition(0, 0) }
    }
}
