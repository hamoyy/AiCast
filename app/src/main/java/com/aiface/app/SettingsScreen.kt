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

/** 设置页（M2，§10）：名称 / 待机主题 / 访问模式（投送 + 密码）/ 本机 */
@Composable
fun SettingsScreen(onClose: () -> Unit, onChanged: () -> Unit) {
    var name by remember { mutableStateOf(Prefs.name) }
    var mode by remember { mutableStateOf(Prefs.mode) }
    var theme by remember { mutableStateOf(Prefs.theme) }
    var cast by remember { mutableStateOf(Prefs.castEnabled) }
    var pwdOn by remember { mutableStateOf(Prefs.mode == "auth") }
    var pwd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

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
            // ① 投送总开关：关掉只是拒绝投送，设备照样被局域网发现
            ToggleRow(
                title = "投送",
                sub = "关闭后仍可被搜索到，但投送会被拒绝",
                checked = cast,
                onToggle = { on ->
                    Prefs.castEnabled = on
                    cast = on
                    onChanged()
                    msg = if (on) "已开启投送"
                    else "已关闭投送（设备仍可被发现，但会拒绝投送）"
                }
            )
            Spacer(Modifier.height(14.dp))
            // ② 密码验证：默认关 = 公开投送；打开才要设密码
            ToggleRow(
                title = "密码验证",
                sub = "关闭 = 公开投送；开启后 agent 需凭密码换 token",
                checked = pwdOn,
                onToggle = { on ->
                    if (on) {
                        // 先展开输入框，等点「确认」才真正生效，避免打开即半配置状态
                        pwdOn = true
                        msg = "请输入密码后点确认"
                    } else {
                        // 关闭不要求验证旧密码：直接清密码 + 吊销全部 token
                        Prefs.clearPassword()
                        Prefs.mode = "public"
                        mode = "public"
                        pwdOn = false
                        pwd = ""
                        onChanged()
                        msg = "已关闭密码验证：密码已清除，全部 token 已吊销"
                    }
                }
            )
            if (pwdOn) {
                Spacer(Modifier.height(12.dp))
                if (Prefs.hasPassword()) {
                    Text("已设置密码 ✓（重填即修改）", color = accent, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                EditRow(pwd, "密码（至少 4 位）", onChange = { pwd = it }, password = true)
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .fillMaxWidth()
                        .background(accent, RoundedCornerShape(10.dp))
                        .clickable {
                            if (pwd.length < 4) msg = "密码太短（至少 4 位）"
                            else {
                                Prefs.setPassword(pwd)   // 内含 revokeAll，旧 token 立即失效
                                Prefs.mode = "auth"
                                mode = "auth"
                                pwd = ""
                                onChanged()
                                msg = "密码已保存（agent 首次连接需向你索取）"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("确认", color = bgDark, fontWeight = FontWeight.Bold) }
            }
        }

        if (mode == "auth" && Prefs.hasPassword()) {
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

/** 一行开关：左边标题+说明，右边 Switch（电视端可靠 D-pad 聚焦） */
@Composable
private fun ToggleRow(
    title: String,
    sub: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = textMain, fontSize = 15.sp)
            Text(sub, color = textDim, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
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
