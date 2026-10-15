package com.aicast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
    var pwdErr by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    var nameEditing by remember { mutableStateOf(false) }
    val nameBox = remember { FocusRequester() }
    val pwdBox = remember { FocusRequester() }
    val pwdSwitchBox = remember { FocusRequester() }
    var focusPwd by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 事件回调（点击/开关/提交）不是 @Composable 作用域，里面不能用 stringResource，
    // 而 msg/pwdErr 恰恰都在回调里赋值 —— 所以在组合期一次性取好，回调直接引用。
    val sNameSaved = stringResource(R.string.settings_name_saved)
    val sCastOn = stringResource(R.string.settings_cast_on)
    val sCastOff = stringResource(R.string.settings_cast_off)
    val sPwdEnterHint = stringResource(R.string.settings_pwd_enter_hint)
    val sPwdOff = stringResource(R.string.settings_pwd_off)
    val sPwdTooShort = stringResource(R.string.settings_pwd_short)
    val sPwdSaved = stringResource(R.string.settings_pwd_saved)
    val sTokenRevoked = stringResource(R.string.settings_token_revoked)

    // 离开设置页就把软键盘收掉：否则窗口会一直停在"给键盘留了位置"的缩小状态，
    // 回到待机页下半屏是黑的（实测 mIsInputViewShown 一直是 true，回主界面也一样）
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    // 焦点：打开密码验证时落到密码框；关闭时把焦点和软键盘一起收掉，
    // 否则焦点会弹回最上面的设备名称输入框（实测就是这个体验问题）
    LaunchedEffect(focusPwd) {
        if (focusPwd) { pwdBox.requestFocus(); focusPwd = false }
    }
    // 关闭密码验证后必须**在重组之后**再处理焦点：写在 click 回调里的话，密码框
    // 还没被移除，等它被移除时系统会做焦点搜索，把焦点塞给最上面的设备名称输入框
    // （实测就是这么跳的）。这里等重组结束，把焦点明确交给「密码验证」那枚开关 ——
    // 只是 clearFocus 不够，清完之后系统仍会挑一个默认目标。
    LaunchedEffect(pwdOn) {
        if (!pwdOn) {
            keyboard?.hide()
            // 密码框被移除的那一帧，系统紧接着会做一次焦点搜索并落到最近的
            // 可聚焦节点（正好是最上面的设备名称输入框）。等它折腾完再把焦点
            // 拉回这枚开关，否则我们的 requestFocus 会被随后那次搜索覆盖。
            delay(120)
            pwdSwitchBox.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            // 背景铺满整屏（含安全区）之后再避让：顺序反了的话，避让带会露出主题纯黑，
            // 和本页的 #0B0E12 拼出一条色差带。只避让屏幕切口——状态栏/导航栏在沉浸
            // 模式下是隐藏的浮层，不需要为它们留位。
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.action_settings),
                color = textMain, fontSize = 24.sp, fontWeight = FontWeight.Bold
            )
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
                    contentDescription = stringResource(R.string.cd_close_settings),
                    tint = textMain,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 设备名称改成「点一下才编辑」：常驻输入框是个焦点磁铁 —— 一旦别处的
        // 输入框被移除，系统会做焦点搜索并把光标塞进最上面这个编辑框（关掉
        // 密码验证时就是这样跳过去的）。改成非编辑态时它压根不是输入框。
        Section(stringResource(R.string.settings_device_name)) {
            if (nameEditing) {
                EditRow(
                    value = name,
                    placeholder = stringResource(R.string.settings_name_placeholder),
                    onChange = { name = it },
                    onDone = { v ->
                        val n = v.trim()
                        if (n.isNotEmpty()) { Prefs.name = n; msg = sNameSaved; onChanged() }
                        nameEditing = false
                        keyboard?.hide()   // 不收键盘的话它会一直盖住下半屏，回待机页还在
                    },
                    modifier = Modifier.focusRequester(nameBox)
                )
                Text(
                    stringResource(R.string.settings_name_done_hint),
                    color = textDim, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                LaunchedEffect(nameEditing) { if (nameEditing) nameBox.requestFocus() }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { nameEditing = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = textMain, fontSize = 16.sp)
                        Text(
                            stringResource(R.string.settings_name_tap_hint),
                            color = textDim, fontSize = 12.sp
                        )
                    }
                    Text(stringResource(R.string.action_modify), color = accent, fontSize = 13.sp)
                }
            }
        }

        // 访问模式放在最上面：投送总开关 + 密码验证，是别人能不能碰这台设备的两道门
        Section(stringResource(R.string.settings_access_mode)) {
            // ① 投送总开关：关掉只是拒绝投送，设备照样被局域网发现
            ToggleRow(
                title = stringResource(R.string.settings_cast_title),
                sub = stringResource(R.string.settings_cast_sub),
                checked = cast,
                onToggle = { on ->
                    Prefs.castEnabled = on
                    cast = on
                    onChanged()
                    msg = if (on) sCastOn else sCastOff
                }
            )
            Spacer(Modifier.height(14.dp))
            // ② 密码验证：默认关 = 公开投送；打开才要设密码
            ToggleRow(
                title = stringResource(R.string.settings_pwd_title),
                sub = stringResource(R.string.settings_pwd_sub),
                focusRequester = pwdSwitchBox,
                checked = pwdOn,
                onToggle = { on ->
                    if (on) {
                        // 先展开输入框，等点「确认」才真正生效，避免打开即半配置状态
                        pwdOn = true
                        pwdErr = null
                        focusPwd = true          // 焦点落到密码框，别跑去顶部的设备名称
                        msg = sPwdEnterHint
                    } else {
                        // 关闭不要求验证旧密码：直接清密码 + 吊销全部 token
                        Prefs.clearPassword()
                        Prefs.mode = "public"
                        mode = "public"
                        pwdOn = false
                        pwd = ""
                        pwdErr = null
                        onChanged()
                        msg = sPwdOff
                    }
                }
            )
            if (pwdOn) {
                Spacer(Modifier.height(12.dp))
                if (Prefs.hasPassword()) {
                    // 不写 ✓：电视系统字体缺该字形会渲染成空白（和 ThemeRow 同理）
                    Text(stringResource(R.string.settings_pwd_set), color = accent, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                EditRow(
                    pwd, stringResource(R.string.settings_pwd_placeholder),
                    onChange = { pwd = it; pwdErr = null },
                    password = true,
                    modifier = Modifier.focusRequester(pwdBox)
                )
                // 错误就地提示：只写在页面底部的 msg 会被长列表顶出屏幕，看着像"点了没反应"
                pwdErr?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE58C8C), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .fillMaxWidth()
                        .background(accent, RoundedCornerShape(10.dp))
                        .clickable {
                            if (pwd.length < 4) {
                                // 带参数的文案没法预先取（pwd.length 在回调里才知道），
                                // 非组合作用域一律走 Str.get
                                pwdErr = Str.get(R.string.settings_pwd_len_err, pwd.length)
                                msg = sPwdTooShort
                            } else {
                                Prefs.setPassword(pwd)   // 内含 revokeAll，旧 token 立即失效
                                Prefs.mode = "auth"
                                mode = "auth"
                                pwd = ""
                                pwdErr = null
                                keyboard?.hide()
                                focus.clearFocus(force = true)
                                onChanged()
                                msg = sPwdSaved
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = bgDark, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Section(stringResource(R.string.settings_theme)) {
            Text(
                stringResource(R.string.settings_theme_desc),
                color = textDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Themes.list().forEach { t ->
                ThemeRow(
                    name = t.name,
                    sub = when {
                        t.kind == "native" -> stringResource(R.string.theme_kind_native)
                        t.source == "builtin" -> stringResource(R.string.theme_kind_builtin_html)
                        else -> stringResource(R.string.theme_kind_agent)
                    },
                    selected = theme == t.id,
                    onClick = {
                        Prefs.theme = t.id
                        theme = t.id
                        HomeRefresh.invalidate()
                        msg = Str.get(R.string.settings_theme_switched, t.name)
                    }
                )
            }
        }

        if (mode == "auth" && Prefs.hasPassword()) {
            Section(stringResource(R.string.settings_tokens)) {
                Info(
                    stringResource(R.string.settings_token_current),
                    stringResource(R.string.settings_token_count, Auth.activeCount())
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF3A2430), RoundedCornerShape(10.dp))
                        .clickable {
                            Auth.revokeAll()
                            msg = sTokenRevoked
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.settings_token_revoke_all),
                        color = Color(0xFFE58C8C), fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Section(stringResource(R.string.settings_device)) {
            Info(stringResource(R.string.settings_device_id), Prefs.deviceId)
            Info(stringResource(R.string.settings_device_addr), "${Net.localIp()}:${Server.port}")
            Info(
                stringResource(R.string.settings_device_form),
                "${DeviceInfo.deviceClass()} · ${stringResource(R.string.settings_device_touch)}:" +
                    if (DeviceInfo.isTouch()) stringResource(R.string.yes)
                    else stringResource(R.string.no)
            )
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
    onToggle: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null
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
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester)
            else Modifier,
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
    password: Boolean = false,
    modifier: Modifier = Modifier
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
        modifier = modifier.fillMaxWidth(),
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
        // minWidth 而非固定 width：英文键名比中文长得多（「Form factor」≈72dp），
        // 写死宽度会把它们压成两行，把值顶下去；这里只保证短键名（ID）也对齐
        Text(k, color = textDim, fontSize = 13.sp, modifier = Modifier.widthIn(min = 56.dp))
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
