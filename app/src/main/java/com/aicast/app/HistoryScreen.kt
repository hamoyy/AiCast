package com.aicast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

private val hText = Color(0xFFE6EAF0)
private val hDim = Color(0xFF8A939E)
private val hCard = Color(0xFF151A21)
private val hDanger = Color(0xFFFF6B6B)

/**
 * 投送历史列表：选中一条即重播。单行可单向删除（不可恢复，故走二次确认）。
 * 遥控器：方向键在各行/按钮间漫游，OK 触发；首行自动获焦，避免电视端进了历史页却没焦点。
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
    val pending = remember { androidx.compose.runtime.mutableStateOf<History.Entry?>(null) }

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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(hCard)
                        .clickable { onClear() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.action_clear), color = hDim, fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
            }
            // 和设置页同一枚圆形关闭按钮：不再靠"右滑/返回键"这种没提示的退出方式
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(hCard, CircleShape)
                    .clip(CircleShape)
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
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    color = hDim, fontSize = 15.sp
                )
            }
        } else {
            val firstReq = remember { FocusRequester() }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { e ->
                    HistoryRow(
                        e = e,
                        onReplay = onReplay,
                        onRequestDelete = { pending.value = e },
                        focusRequester = if (e == entries.first()) firstReq else null
                    )
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
            // 进入历史页就把焦点交给第一条，电视端方向键立即可用
            LaunchedEffect(entries.firstOrNull()?.id) {
                if (entries.isNotEmpty()) firstReq.requestFocus()
            }
        }
    }

    // 二次确认：删除不可恢复，触摸端弹层、电视端 OK 确认
    pending.value?.let { target ->
        DeleteConfirmDialog(
            entry = target,
            onConfirm = {
                onDelete(target)
                pending.value = null
            },
            onDismiss = { pending.value = null }
        )
    }
}

@Composable
private fun HistoryRow(
    e: History.Entry,
    onReplay: (History.Entry) -> Unit,
    onRequestDelete: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val replayable = !e.truncated && e.html != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(hCard)
            .focusable()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 电视端：行聚焦时 OK = 重播（不可重播的行则无操作）
            .onKeyEvent { ev ->
                if (ev.action == androidx.compose.ui.input.key.KeyEventAction.Down &&
                    ev.key == Key.Enter && replayable
                ) {
                    onReplay(e); true
                } else false
            }
            .clickable(enabled = replayable) { onReplay(e) }
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
        DeleteButton(onRequestDelete)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (replayable) R.string.history_replay else R.string.history_not_restorable),
            color = if (replayable) accent else hDim,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DeleteButton(onRequestDelete: () -> Unit) {
    val focused = remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused.value) hDanger.copy(alpha = 0.18f) else hCard)
            .border(
                if (focused.value) 1.5.dp else 0.dp,
                if (focused.value) hDanger else Color.Transparent,
                CircleShape
            )
            .focusable(true)
            .onFocusChanged { focused.value = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.action == androidx.compose.ui.input.key.KeyEventAction.Down &&
                    ev.key == Key.Enter
                ) {
                    onRequestDelete(); true
                } else false
            }
            .clickable { onRequestDelete() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.cd_delete_entry),
            tint = if (focused.value) hDanger else hDim,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: History.Entry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmReq = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(hCard)
                .clickable(enabled = false) {} // 吞掉背景点击，避免穿透到列表
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.history_confirm_delete_title),
                color = hText, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                entry.title.ifEmpty { stringResource(R.string.history_untitled) },
                color = hDim, fontSize = 13.sp, maxLines = 1
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF202730))
                        .clickable { onDismiss() }
                        .focusable()
                        .focusRequester(confirmReq)
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.action_cancel),
                        color = hText, fontSize = 14.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(hDanger.copy(alpha = 0.16f))
                        .border(1.dp, hDanger, RoundedCornerShape(12.dp))
                        .clickable { onConfirm() }
                        .focusable()
                        .onKeyEvent { ev ->
                            if (ev.action == androidx.compose.ui.input.key.KeyEventAction.Down &&
                                ev.key == Key.Enter
                            ) {
                                onConfirm(); true
                            } else false
                        }
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = hDanger, fontSize = 14.sp
                    )
                }
            }
        }
        LaunchedEffect(Unit) { confirmReq.requestFocus() }
    }
}
