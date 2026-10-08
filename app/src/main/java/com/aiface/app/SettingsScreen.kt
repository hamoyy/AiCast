package com.aiface.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val bgDark = Color(0xFF0B0E12)
private val cardBg = Color(0xFF151A21)
private val textMain = Color(0xFFE6EAF0)
private val textDim = Color(0xFF8A939E)

private val permLabelZh = mapOf(
    "render" to "投送内容 render",
    "message" to "增量消息 message",
    "param" to "参数控制 param",
    "screen" to "屏幕控制 screen",
    "system" to "系统控制 system",
    "screenshot" to "截屏 screenshot",
    "eval" to "JS 注入 eval（仅本机开）"
)

/** 设置页（M2，§10）：名称 / 模式 / 逐项权限 / 密码 */
@Composable
fun SettingsScreen(onClose: () -> Unit, onChanged: () -> Unit) {
    var name by remember { mutableStateOf(Prefs.name) }
    var mode by remember { mutableStateOf(Prefs.mode) }
    var theme by remember { mutableStateOf(Prefs.theme) }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var openMap by remember {
        mutableStateOf(Prefs.openable.associateWith { Prefs.isOpen(it) })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", color = textMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(cardBg, CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                // 用矢量图标而非文本"✕"：电视系统字体缺该字形会渲染成空白
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭设置",
                    tint = textMain,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Section("设备名称") {
            EditRow(
                value = name,
                placeholder = "让 agent 知道这是哪台设备",
                onChange = { name = it },
                onDone = { v ->
                    val n = v.trim()
                    if (n.isNotEmpty()) {
                        Prefs.name = n
                        msg = "名称已保存"
                        onChanged()
                    }
                }
            )
        }

        Section("待机主题") {
            Text(
                "原生时钟常驻省电；HTML 主题整屏由页面自绘，含 agent 注册的自定义首页",
                color = textDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Themes.list().forEach { t ->
                ThemeRow(
                    name = t.name,
                    sub = when {
                        t.kind == "native" -> "内置 · 省电常驻"
                        t.source == "builtin" -> "内置 HTML"
                        else -> "agent 注册"
                    },
                    selected = theme == t.id,
                    onClick = {
                        Prefs.theme = t.id
                        theme = t.id
                        HomeRefresh.invalidate()
                        msg = "已切换主题：${t.name}"
                    }
                )
            }
        }

        Section("访问模式") {
            Row {
                ModeChip("公开 · 谁能连谁投", mode == "public") {
                    Prefs.mode = "public"; mode = "public"; onChanged(); msg = "已切换：公开"
                }
                Spacer(Modifier.width(10.dp))
                ModeChip("需密码 · agent 需验证", mode == "auth") {
                    Prefs.mode = "auth"; mode = "auth"; onChanged(); msg = "已切换：需密码"
                }
            }
            Spacer(Modifier.height(10.dp))
            if (Prefs.mode == "auth") {
                if (Prefs.hasPassword()) {
                    Text("已设置密码 ✓", color = accent, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                EditRow(pwd, "新密码（至少 4 位）", onChange = { pwd = it }, password = true)
                Spacer(Modifier.height(8.dp))
                EditRow(pwd2, "再输一遍确认", onChange = { pwd2 = it }, password = true)
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .fillMaxWidth()
                        .background(accent, RoundedCornerShape(10.dp))
                        .clickable {
                            if (pwd.length < 4) msg = "密码太短（至少 4 位）"
                            else if (pwd != pwd2) msg = "两次输入不一致"
                            else {
                                Prefs.setPassword(pwd); pwd = ""; pwd2 = ""
                                msg = "密码已保存（agent 首次连接需向你索取）"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("保存密码", color = bgDark, fontWeight = FontWeight.Bold) }
            }
        }

        if (Prefs.mode == "auth" && Prefs.hasPassword()) {
            Section("已授权的设备（token 30 天有效）") {
                Info("当前", "${Auth.activeCount()} 个 agent 持有有效 token")
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF3A2430), RoundedCornerShape(10.dp))
                        .clickable { Auth.revokeAll(); msg = "已吊销全部 token（agent 需重新输密码）" },
                    contentAlignment = Alignment.Center
                ) { Text("吊销全部 token", color = Color(0xFFE58C8C), fontWeight = FontWeight.Bold) }
            }
        }

        Section("投送权限（公开模式下逐项放行；需密码模式验证后同样按此判定）") {
            Prefs.openable.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        permLabelZh[p] ?: p,
                        color = textMain, fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = openMap[p] ?: false,
                        onCheckedChange = { on ->
                            Prefs.setOpen(p, on)
                            openMap = openMap + (p to on)
                            onChanged(); msg = "已更新权限：$p"
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = accent,
                            checkedThumbColor = bgDark,
                            uncheckedTrackColor = cardBg,
                            uncheckedThumbColor = textDim,
                            uncheckedBorderColor = Color(0xFF2A313B)
                        )
                    )
                }
            }
        }

        Section("本机") {
            Info("ID", Prefs.deviceId)
            Info("地址", "${Net.localIp()}:${Server.port}")
            Info("形态", "${DeviceInfo.deviceClass()} · 触摸:${if (DeviceInfo.isTouch()) "是" else "否"}")
        }

        msg?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = accent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, color = textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(4.dp))
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) accent else Color(0xFF202730),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) bgDark else textDim,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun EditRow(
    value: String,
    placeholder: String,
    onDone: (String) -> Unit = {},
    onChange: (String) -> Unit = {},
    password: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = textDim, fontSize = 14.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onDone(value) }
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF202730),
            unfocusedContainerColor = Color(0xFF202730),
            focusedTextColor = textMain,
            unfocusedTextColor = textMain,
            cursorColor = accent,
            focusedIndicatorColor = accent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun Info(k: String, v: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(k, color = textDim, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Text(v, color = textMain, fontSize = 13.sp)
    }
}

@Composable
private fun ThemeRow(name: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF1B2A24) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (selected) accent else textMain,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(sub, color = textDim, fontSize = 12.sp)
        }
        // 选中态用小圆点而非"✓"字形：电视系统字体缺该字形
        if (selected) Box(
            modifier = Modifier
                .size(12.dp)
                .background(accent, CircleShape)
        )
    }
}
