package com.aicast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val hText = Color(0xFFE6EAF0)
private val hDim = Color(0xFF8A939E)
private val hCard = Color(0xFF151A21)

/** 投送历史列表：选中一条即重播。遥控器可用方向键聚焦、OK 重播，返回键关闭。 */
@Composable
fun HistoryScreen(
    entries: List<History.Entry>,
    onReplay: (History.Entry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val cfg = LocalConfiguration.current
    val titleSize = (cfg.screenWidthDp / 22f).sp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E12))
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { e ->
                    HistoryRow(e, onReplay)
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: History.Entry, onReplay: (History.Entry) -> Unit) {
    val replayable = !e.truncated && e.html != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(hCard)
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
        Text(
            stringResource(if (replayable) R.string.history_replay else R.string.history_not_restorable),
            color = if (replayable) accent else hDim,
            fontSize = 13.sp
        )
    }
}
