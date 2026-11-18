package com.aicast.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 4.4 兼容界面的最小控件库。
 *
 * 为什么不用 XML 布局：这些界面只服务 API 19~20 一小撮设备，写成一屏一屏的 XML + inflate
 * 反而增加维护面；这里全部用代码构建，配色/圆角/焦点反馈集中在一处，行为与新界面对齐。
 *
 * 焦点反馈是硬要求：电视盒子/遥控器上必须看得出焦点在哪一行（"能聚焦但看不出来 = 没法操作"）。
 */
object LegacyUi {

    val bg = Color.parseColor("#0B0E12")
    val card = Color.parseColor("#151A21")
    val cardFocus = Color.parseColor("#20262F")
    // 注意别叫 text：TextView.apply 里的 `text` 是它自己的属性，会遮蔽同名字段
    val fg = Color.parseColor("#E6EAF0")
    val dim = Color.parseColor("#8A939E")
    val accent = Color.parseColor("#4CC2A5")
    val warn = Color.parseColor("#E5B567")

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun label(ctx: Context, s: CharSequence, sizeSp: Float, color: Int = fg): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }

    fun sectionTitle(ctx: Context, s: CharSequence): TextView =
        label(ctx, s, 13f, dim).apply {
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 6))
        }

    /** 卡片底：圆角 + 聚焦时提亮描边 */
    fun rowBackground(ctx: Context, focused: Boolean, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (focused) cardFocus else card)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (focused) setStroke(dp(ctx, 2), accent)
        }

    /**
     * 可点击行：标题 + 可选副标题。触摸点击与 D-pad 确认键走同一个回调。
     */
    fun row(
        ctx: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = rowBackground(ctx, false)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }
        ll.addView(label(ctx, title, 15f))
        if (subtitle != null) {
            ll.addView(label(ctx, subtitle, 12f, dim).apply {
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
        ll.setOnFocusChangeListener { _, focused -> ll.background = rowBackground(ctx, focused) }
        ll.setOnClickListener { onClick() }
        return ll
    }

    /** 小圆角按钮/浮标 */
    fun chip(ctx: Context, s: CharSequence, onClick: () -> Unit): TextView {
        fun bg(focused: Boolean) = GradientDrawable().apply {
            setColor(if (focused) Color.parseColor("#E62A323D") else Color.parseColor("#80151A21"))
            cornerRadius = dp(ctx, 20).toFloat()
            if (focused) setStroke(dp(ctx, 2), accent)
        }
        return TextView(ctx).apply {
            text = s
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 18), dp(ctx, 7), dp(ctx, 18), dp(ctx, 7))
            background = bg(false)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focused -> v.background = bg(focused) }
            setOnClickListener { onClick() }
        }
    }

    /** 主/次按钮（表单里用，宽度自适应） */
    fun button(ctx: Context, s: CharSequence, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(if (primary) Color.parseColor("#0B0E12") else fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 18), dp(ctx, 9))
            background = GradientDrawable().apply {
                setColor(if (primary) accent else card)
                cornerRadius = dp(ctx, 10).toFloat()
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focused ->
                v.background = GradientDrawable().apply {
                    setColor(if (primary) accent else if (focused) cardFocus else card)
                    cornerRadius = dp(ctx, 10).toFloat()
                    if (focused) setStroke(dp(ctx, 2), accent)
                }
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1E252D"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, 12) }
    }
}
