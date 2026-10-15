package com.aicast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val hText = Color(0xFFE6EAF0)
private val hDim = Color(0xFF8A939E)
private val hCard = Color(0xFF151A21)
private val hCardFocus = Color(0xFF20262F)
private val hDanger = Color(0xFFFF6B6B)

/**
 * 投送历史列表：选中一条即重播。单行可单向删除（不可恢复，故走二次确认）。
 *
 * 电视端关键三点（踩过的坑）：
 * 1. 焦点必须有视觉反馈——行只 focusable 不加高亮，焦点在但用户看不见，等于不可操作。
 * 2. 用 clickable 单一修饰符提供"聚焦 + 触摸点击 + OK 激活"，别再叠 focusable()，
 *    否则同一行出现两个焦点目标，方向键要按两次。
 * 3. 删除按钮嵌在行卡片内部，Compose 的方向键搜索不会进入父节点边界内的子元素，
 *    必须用 focusProperties 显式连"行 --右键--> 删除按钮 / 删除按钮 --左键--> 行"。
 */
@Composable
fun HistoryScreen(
    entries: List<History.Entry>,
    onReplay: (History.Entry) -> Unit,
    onDelete: (History.Entry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val cfg = LocalConfiguration.current
    val titleSize = (cfg.screenWidthDp / 22f).sp
    // 待删除的那一条：非 null 时弹出确认层
    val pending = remember { mutableStateOf<History.Entry?>(null) }
    // 清空全部也要二次确认（不可恢复）
    val pendingClear = remember { mutableStateOf(false) }
    // 清空后列表变空，页面上只剩关闭按钮可聚焦，主动把焦点交给它，避免电视端"焦点丢失"
    val closeReq = remember { FocusRequester() }
    // 每行一对焦点请求器：行本身、行内删除按钮。列表变化时重建。
    val reqs = remember(entries) {
        entries.associate { it.id to Pair(FocusRequester(), FocusRequester()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E12))
            // 背景铺满整屏（含安全区）之后再避让：顺序反了的话，避让带会露出主题纯黑，
            // 和本页的 #0B0E12 拼出一条色差带。只避让屏幕切口——状态栏/导航栏在沉浸
            // 模式下是隐藏的浮层，不需要为它们留位。
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.history_title),
                    color = hText, fontSize = titleSize, fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.history_subtitle),
                    color = hDim, fontSize = (cfg.screenWidthDp / 42f).sp
                )
            }
            if (entries.isNotEmpty()) {
                ClearButton { pendingClear.value = true }
                Spacer(Modifier.width(8.dp))
            }
            // 和设置页同一枚圆形关闭按钮：不再靠"右滑/返回键"这种没提示的退出方式
            CloseButton(onBack, closeReq)
        }
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    color = hDim, fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { e ->
                    val (rowReq, delReq) = reqs.getValue(e.id)
                    HistoryRow(
                        e = e,
                        onReplay = onReplay,
                        onRequestDelete = { pending.value = e },
                        rowRequester = rowReq,
                        deleteRequester = delReq
                    )
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
            // 进入历史页就把焦点交给第一条，电视端方向键立即可用
            LaunchedEffect(entries.firstOrNull()?.id) {
                entries.firstOrNull()?.let { reqs[it.id]?.first?.requestFocus() }
            }
        }

        // 列表空了（清空后）：把焦点交给关闭按钮，别让电视端焦点悬空
        LaunchedEffect(entries.isEmpty()) {
            if (entries.isEmpty()) closeReq.requestFocus()
        }
    }

    // 二次确认：删除/清空都不可恢复，触摸端弹层、电视端 OK 确认
    pending.value?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.history_confirm_delete_title),
            detail = target.title.ifEmpty { stringResource(R.string.history_untitled) },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(target)
                pending.value = null
            },
            onDismiss = { pending.value = null }
        )
    }
    if (pendingClear.value) {
        ConfirmDialog(
            title = stringResource(R.string.history_confirm_clear_title),
            detail = stringResource(R.string.history_confirm_clear_detail, entries.size),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                onClear()
                pendingClear.value = false
            },
            onDismiss = { pendingClear.value = false }
        )
    }
}

/** 顶部「清空」：带焦点高亮，遥控器可聚焦 */
@Composable
private fun ClearButton(onClear: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused.value) hCardFocus else hCard)
            .border(
                if (focused.value) 2.dp else 0.dp,
                if (focused.value) accent else Color.Transparent,
                shape
            )
            .onFocusChanged { focused.value = it.isFocused }
            .clickable { onClear() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            stringResource(R.string.action_clear),
            color = if (focused.value) hText else hDim,
            fontSize = 13.sp
        )
    }
}

/** 顶部圆形关闭按钮 */
@Composable
private fun CloseButton(onBack: () -> Unit, focusRequester: FocusRequester? = null) {
    val focused = remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused.value) hCardFocus else hCard)
            .border(
                if (focused.value) 2.dp else 0.dp,
                if (focused.value) accent else Color.Transparent,
                CircleShape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused.value = it.isFocused }
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_history),
            tint = hText,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun HistoryRow(
    e: History.Entry,
    onReplay: (History.Entry) -> Unit,
    onRequestDelete: () -> Unit,
    rowRequester: FocusRequester,
    deleteRequester: FocusRequester
) {
    val replayable = !e.truncated && e.html != null
    val focused = remember { mutableStateOf(false) }
    // clickable 一个修饰符就够：触摸点击 + 电视端聚焦 + OK/DPAD_CENTER 激活全由它提供。
    // 不可还原的行也保持可聚焦（否则删不掉），只是 OK 不做重播。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused.value) hCardFocus else hCard)
            .border(
                if (focused.value) 2.dp else 0.dp,
                if (focused.value) accent else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .focusRequester(rowRequester)
            // 行聚焦后按右键 → 行内删除按钮
            .focusProperties { right = deleteRequester }
            .onFocusChanged { focused.value = it.isFocused }
            .clickable { if (replayable) onReplay(e) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                color = if (replayable) hText else hDim,
                fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            // CIO 的 remoteHost 有时返回非 IP 的十六进制串，只在像地址时才展示
            val viaLabel =
                if (e.via.contains('.') || e.via.contains(':')) e.via
                else stringResource(R.string.history_via_lan)
            val sizeLabel =
                if (e.size < 1024) stringResource(R.string.history_size_b, e.size)
                else stringResource(R.string.history_size_kb, e.size / 1024f)
            val meta = "${History.formatTime(e.ts)}  ·  $viaLabel  ·  $sizeLabel" +
                if (e.truncated) "  ·  " + stringResource(R.string.history_truncated) else ""
            Text(meta, color = hDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        // 单向删除按钮：触摸点击 / 电视方向键聚焦后 OK 触发，均走二次确认
        DeleteButton(onRequestDelete, deleteRequester, rowRequester)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (replayable) R.string.history_replay else R.string.history_not_restorable),
            color = if (replayable) accent else hDim,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DeleteButton(
    onRequestDelete: () -> Unit,
    deleteRequester: FocusRequester,
    rowRequester: FocusRequester
) {
    val focused = remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused.value) hDanger.copy(alpha = 0.22f) else hCard)
            .border(
                if (focused.value) 2.dp else 0.dp,
                if (focused.value) hDanger else Color.Transparent,
                CircleShape
            )
            .focusRequester(deleteRequester)
            // 删除按钮按左键 → 回到本行
            .focusProperties { left = rowRequester }
            .onFocusChanged { focused.value = it.isFocused }
            .clickable { onRequestDelete() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.cd_delete_entry),
            tint = if (focused.value) hDanger else hDim,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelReq = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(hCard)
                // 吞掉背景点击，避免穿透到列表
                .clickable(enabled = false) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                color = hText, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                detail,
                color = hDim, fontSize = 13.sp, maxLines = 1
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogAction(
                    label = stringResource(R.string.action_cancel),
                    color = hText,
                    focusRequester = cancelReq,
                    onClick = onDismiss
                )
                DialogAction(
                    label = confirmLabel,
                    color = hDanger,
                    danger = true,
                    onClick = onConfirm
                )
            }
        }
        // 默认焦点落在「取消」：误按 OK 不会直接删掉，符合破坏性操作的安全默认
        LaunchedEffect(Unit) { cancelReq.requestFocus() }
    }
}

@Composable
private fun DialogAction(
    label: String,
    color: Color,
    focusRequester: FocusRequester? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val focused = remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    focused.value && danger -> hDanger.copy(alpha = 0.28f)
                    focused.value -> Color(0xFF2A323D)
                    danger -> hDanger.copy(alpha = 0.14f)
                    else -> Color(0xFF202730)
                }
            )
            .border(
                if (focused.value) 2.dp else 0.dp,
                if (focused.value) (if (danger) hDanger else accent) else Color.Transparent,
                shape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused.value = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 11.dp)
    ) {
        Text(label, color = color, fontSize = 14.sp)
    }
}
