package com.aicast.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4.4 兼容版的设置页与投送历史页（平台控件实现）。
 *
 * 功能对齐 Compose 版能改的项：设备名称 / 访问模式（投送开关 + 密码）/ 待机主题 /
 * 本机信息 / 检查更新。**没有**做 token 列表这种列表型管理，4.4 上只保留「吊销全部」
 * 的等价操作 —— 关掉密码即吊销，与 Compose 版一致。
 */
object LegacyPanels {

    fun settings(
        act: Activity,
        update: LegacyUpdate,
        onClose: () -> Unit,
        onRebuild: () -> Unit,
        onChanged: () -> Unit
    ): View {
        val ctx = act
        var firstFocus: View? = null

        val col = column(ctx)
        col.addView(header(ctx, Str.get(R.string.action_settings), onClose))

        // ---------- 设备名称 ----------
        col.addView(LegacyUi.sectionTitle(ctx, Str.get(R.string.settings_device_name)))
        val nameBox = input(ctx, Prefs.name, singleLine = true)
        col.addView(nameBox)
        rowOf(ctx, LegacyUi.button(ctx, Str.get(R.string.action_confirm), primary = true) {
            val v = nameBox.text.toString().trim()
            if (v.isEmpty()) return@button
            Prefs.name = v
            onChanged()
            onRebuild()
            toast(ctx, Str.get(R.string.settings_name_saved))
        }) { firstFocus = it }

        // ---------- 访问模式 ----------
        col.addView(LegacyUi.sectionTitle(ctx, Str.get(R.string.settings_access_mode)))
        val pwdOn = Prefs.mode == "auth"

        col.addView(toggleRow(
            ctx,
            Str.get(R.string.settings_pwd_title),
            if (pwdOn) Str.get(R.string.settings_pwd_set) else Str.get(R.string.settings_pwd_enter_hint),
            pwdOn
        ) { on ->
            if (on) {
                // 打开开关只是展开输入框，真正生效要等用户设好密码
                Prefs.mode = "auth"
                onRebuild()
            } else {
                Prefs.clearPassword()
                Prefs.mode = "public"
                toast(ctx, Str.get(R.string.settings_pwd_off))
                onRebuild()
            }
        })

        if (Prefs.mode == "auth") {
            val pwd = input(ctx, "", singleLine = true, password = true)
            pwd.hint = Str.get(R.string.settings_pwd_placeholder)
            col.addView(pwd)
            rowOf(ctx, LegacyUi.button(ctx, Str.get(R.string.action_confirm), primary = true) {
                val v = pwd.text.toString()
                if (v.length < 4) {
                    toast(ctx, Str.get(R.string.settings_pwd_len_err, v.length))
                } else {
                    Prefs.setPassword(v)
                    Prefs.mode = "auth"
                    toast(ctx, Str.get(R.string.settings_pwd_saved))
                    onRebuild()
                }
            }) { }
        }

        col.addView(LegacyUi.divider(ctx))
        col.addView(toggleRow(
            ctx,
            Str.get(R.string.settings_cast_title),
            Str.get(R.string.settings_cast_sub),
            Prefs.castEnabled
        ) { on ->
            Prefs.castEnabled = on
            toast(ctx, if (on) Str.get(R.string.settings_cast_on) else Str.get(R.string.settings_cast_off))
            onChanged()
        })

        // ---------- 待机主题 ----------
        col.addView(LegacyUi.sectionTitle(ctx, Str.get(R.string.settings_theme)))
        Themes.list().forEach { t ->
            val current = t.id == Prefs.theme
            val kindLabel = when (t.kind) {
                "native" -> Str.get(R.string.theme_kind_native)
                "html" -> if (t.source == "agent") Str.get(R.string.theme_kind_agent)
                else Str.get(R.string.theme_kind_builtin_html)
                else -> ""
            }
            val rowView = LegacyUi.row(
                ctx,
                (if (current) "✓ " else "") + t.name,
                kindLabel
            ) {
                Prefs.theme = t.id
                HomeRefresh.invalidate()
                toast(ctx, Str.get(R.string.settings_theme_switched, t.name))
                onRebuild()
            }
            col.addView(rowView)
        }

        // ---------- 本机 ----------
        col.addView(LegacyUi.sectionTitle(ctx, Str.get(R.string.settings_device)))
        col.addView(infoLine(ctx, Str.get(R.string.settings_device_id), Prefs.deviceId))
        col.addView(infoLine(ctx, Str.get(R.string.settings_device_addr), "${Net.localIp()}:${Server.port}"))
        col.addView(infoLine(ctx, Str.get(R.string.settings_device_form), DeviceInfo.deviceClass()))
        col.addView(infoLine(ctx, Str.get(R.string.legacy_system), "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"))
        col.addView(infoLine(ctx, Str.get(R.string.legacy_webview), webViewLabel(ctx)))
        col.addView(infoLine(ctx, Str.get(R.string.about_version), "${Updater.currentVersionName(ctx)} (${Updater.currentVersionCode(ctx)})"))

        // ---------- 更新 ----------
        col.addView(LegacyUi.sectionTitle(ctx, Str.get(R.string.settings_about)))
        val status = LegacyUi.label(ctx, "", 13f, LegacyUi.dim)
        col.addView(rowOf(ctx, LegacyUi.button(ctx, Str.get(R.string.update_check)) {
            status.text = Str.get(R.string.update_checking)
            update.checkManually { msg ->
                status.text = msg
                toast(ctx, msg)
            }
        }) { })
        col.addView(status)

        val scroll = wrap(ctx, col)
        scroll.post { firstFocus?.requestFocus() }
        return scroll
    }

    fun history(
        act: Activity,
        onReplay: (History.Entry) -> Unit,
        onClose: () -> Unit,
        onRebuild: () -> Unit
    ): View {
        val ctx = act
        val col = column(ctx)
        col.addView(header(ctx, Str.get(R.string.history_title), onClose, extra = {
            if (History.list().isNotEmpty()) {
                LegacyUi.chip(ctx, Str.get(R.string.action_clear)) {
                    AlertDialog.Builder(ctx)
                        .setTitle(Str.get(R.string.history_confirm_clear_title))
                        .setMessage(Str.get(R.string.history_confirm_clear_detail))
                        .setPositiveButton(Str.get(R.string.action_confirm)) { _, _ ->
                            History.clear(); onRebuild()
                        }
                        .setNegativeButton(Str.get(R.string.action_cancel), null)
                        .show()
                }
            }
        }))

        val entries = History.list()
        if (entries.isEmpty()) {
            col.addView(LegacyUi.label(ctx, Str.get(R.string.history_empty), 14f, LegacyUi.dim).apply {
                setPadding(0, LegacyUi.dp(ctx, 24), 0, 0)
            })
        } else {
            entries.forEach { e ->
                col.addView(historyRow(ctx, e, onReplay = { onReplay(e) }, onDelete = {
                    AlertDialog.Builder(ctx)
                        .setTitle(Str.get(R.string.history_confirm_delete_title))
                        .setPositiveButton(Str.get(R.string.action_confirm)) { _, _ ->
                            History.remove(e.id); onRebuild()
                        }
                        .setNegativeButton(Str.get(R.string.action_cancel), null)
                        .show()
                }))
            }
        }

        val scroll = wrap(ctx, col)
        scroll.post { col.getChildAt(1)?.requestFocus() }
        return scroll
    }

    // ---------------- 组件 ----------------

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(LegacyUi.bg)
        setPadding(LegacyUi.dp(ctx, 20), LegacyUi.dp(ctx, 18), LegacyUi.dp(ctx, 20), LegacyUi.dp(ctx, 32))
    }

    private fun wrap(ctx: Context, content: LinearLayout): ScrollView =
        ScrollView(ctx).apply {
            setBackgroundColor(LegacyUi.bg)
            isFillViewport = true
            // 面板本身聚焦不到子控件时，按 D-pad 仍能把焦点移进来
            isFocusableInTouchMode = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

    private fun header(ctx: Context, title: String, onClose: () -> Unit, extra: ((LinearLayout) -> Unit)? = null): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LegacyUi.label(ctx, title, 24f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        })
        extra?.invoke(row)
        row.addView(LegacyUi.chip(ctx, Str.get(R.string.update_close), onClose).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = LegacyUi.dp(ctx, 10) }
        })
        return row
    }

    private fun rowOf(ctx: Context, vararg children: View, onFirst: (View) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, LegacyUi.dp(ctx, 8), 0, 0)
        }
        children.forEachIndexed { i, v ->
            row.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = LegacyUi.dp(ctx, 10) })
        }
        onFirst(row.getChildAt(0))
        return row
    }

    private fun input(ctx: Context, value: String, singleLine: Boolean, password: Boolean = false): EditText =
        EditText(ctx).apply {
            setText(value)
            isSingleLine = singleLine
            setTextColor(LegacyUi.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setHintTextColor(LegacyUi.dim)
            setPadding(LegacyUi.dp(ctx, 12), LegacyUi.dp(ctx, 10), LegacyUi.dp(ctx, 12), LegacyUi.dp(ctx, 10))
            background = LegacyUi.rowBackground(ctx, false)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LegacyUi.dp(ctx, 8) }
        }

    private fun toggleRow(
        ctx: Context,
        title: String,
        sub: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(LegacyUi.dp(ctx, 14), LegacyUi.dp(ctx, 10), LegacyUi.dp(ctx, 14), LegacyUi.dp(ctx, 10))
            background = LegacyUi.rowBackground(ctx, false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LegacyUi.dp(ctx, 8) }
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        texts.addView(LegacyUi.label(ctx, title, 15f))
        texts.addView(LegacyUi.label(ctx, sub, 12f, LegacyUi.dim).apply {
            setPadding(0, LegacyUi.dp(ctx, 3), 0, 0)
        })
        val sw = Switch(ctx).apply {
            isChecked = checked
            // 焦点统一落在整行上：D-pad 的左右键留给页面导航，确认键切换
            isFocusable = false
            isClickable = false
        }
        row.addView(texts)
        row.addView(sw)
        row.setOnFocusChangeListener { _, focused -> row.background = LegacyUi.rowBackground(ctx, focused) }
        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
        return row
    }

    private fun infoLine(ctx: Context, k: String, v: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, LegacyUi.dp(ctx, 5), 0, 0)
            addView(LegacyUi.label(ctx, k, 13f, LegacyUi.dim).apply { minWidth = LegacyUi.dp(ctx, 74) })
            addView(LegacyUi.label(ctx, v, 13f))
        }

    private fun historyRow(
        ctx: Context,
        e: History.Entry,
        onReplay: () -> Unit,
        onDelete: () -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(LegacyUi.dp(ctx, 14), LegacyUi.dp(ctx, 10), LegacyUi.dp(ctx, 14), LegacyUi.dp(ctx, 10))
            background = LegacyUi.rowBackground(ctx, false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LegacyUi.dp(ctx, 8) }
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val title = e.title.ifBlank { Str.get(R.string.history_untitled) }
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.ts))
        val via = if (e.url != null) Str.get(R.string.history_kind_url) else Str.get(R.string.history_via_lan)
        val tail = if (e.truncated) " · ${Str.get(R.string.history_truncated)}" else ""
        texts.addView(LegacyUi.label(ctx, title, 15f))
        texts.addView(LegacyUi.label(ctx, "$time · $via$tail", 12f, LegacyUi.dim).apply {
            setPadding(0, LegacyUi.dp(ctx, 3), 0, 0)
        })
        row.addView(texts)
        row.addView(LegacyUi.chip(ctx, Str.get(R.string.action_delete), onDelete))
        row.setOnFocusChangeListener { _, focused -> row.background = LegacyUi.rowBackground(ctx, focused) }
        row.setOnClickListener { onReplay() }
        return row
    }

    private fun webViewLabel(ctx: Context): String = try {
        val ua = android.webkit.WebSettings.getDefaultUserAgent(ctx)
        Regex("Chrome/([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "unknown"
    } catch (e: Exception) { "unknown" }

    private fun toast(ctx: Context, s: String) {
        if (s.isNotEmpty()) Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }
}
