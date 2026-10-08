package com.aiface.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val labelColor = Color(0xFF8A939E)
private val valueColor = Color(0xFFE6EAF0)
internal val accent = Color(0xFF4CC2A5)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val secFmt = SimpleDateFormat("ss", Locale.getDefault())
private val dateFmt = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
// JetBrains Mono ExtraBold（OFL，docs/licenses/）：数字等宽不抖动，
// 时间和日期共用同一字体族，左侧视觉基线一致；日期里的中文自动回退系统字体
private val clockFont = FontFamily(Font(R.font.jetbrains_mono_extrabold))

@Composable
fun aifaceColors() = androidx.compose.material3.darkColorScheme(
    // 纯黑底：AMOLED 上黑像素子像素直接熄灭，待机常亮更省电
    background = Color.Black,
    surface = Color.Black,
    primary = accent,
    onPrimary = Color(0xFF0B0E12),
)

/**
 * 待机时钟大屏（§10）：响应式、不按设备类型分支。
 * 顶栏=名称+状态徽标；中部=时钟/日期；底栏=IP:端口/deviceId/开放权限。
 * 背景为低干扰科技动效（漂移光晕 + 细点阵），时间用等宽数字防抖动。
 */
@Composable
fun ClockScreen(onSettings: () -> Unit = {}, onHistory: () -> Unit = {}) {
    val cfg = LocalConfiguration.current
    val openLabels = Prefs.openList().joinToString(" ") { permLabel(it) }
    // 时间字号：横屏按高度收敛（避免大屏字号过大压住日期），竖屏按宽度
    val timeSize = (min(cfg.screenWidthDp / 7f, cfg.screenHeightDp / 6f) * paramsFontMultiplier()).sp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // 手机端：首页右滑 → 打开投送历史
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                    onDragEnd = { if (total > 160f) onHistory() }
                )
            }
    ) {
        TechBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Prefs.name,
                    color = valueColor,
                    fontSize = (cfg.screenWidthDp / 40f).sp,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                val isPublic = Prefs.mode == "public"
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (isPublic) accent else Color(0xFFE5A64B), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPublic) "公开投送" else "需密码",
                    color = labelColor,
                    fontSize = (cfg.screenWidthDp / 55f).sp
                )
                IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = labelColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 中部时钟（tick 状态局限在本组件，每秒只重组这一块）
            ClockBlock(timeSize = timeSize)

            // 底栏信息条
            Column {
                Text(
                    "IP  ${Net.localIp()}:${Server.PORT}",
                    color = labelColor, fontSize = (cfg.screenWidthDp / 45f).sp
                )
                Text(
                    "ID  ${Prefs.deviceId}    开放: ${if (openLabels.isEmpty()) "无" else openLabels}",
                    color = labelColor, fontSize = (cfg.screenWidthDp / 45f).sp
                )
            }
        }
    }
}

@Composable
private fun ClockBlock(timeSize: androidx.compose.ui.unit.TextUnit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis()
        }
    }
    val cfg = LocalConfiguration.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        // 等宽数字：秒/分变化时字宽恒定，日期不再左右抖动
        Text(
            timeFmt.format(Date(now)) + ":" + secFmt.format(Date(now)),
            color = valueColor,
            fontSize = timeSize,
            fontFamily = clockFont,
            letterSpacing = (-0.02f).sp
        )
        Spacer(Modifier.size((cfg.screenWidthDp / 60f).dp))
        Text(
            dateFmt.format(Date(now)),
            color = labelColor,
            fontSize = (cfg.screenWidthDp / 24f).sp,
            fontFamily = clockFont
        )
        // 预留：天气/自定义信息位（M 后续按需接入）
        Spacer(Modifier.size((cfg.screenWidthDp / 30f).dp))
    }
}

/**
 * 背景科技动效：每次启动随机生成几条彩色正弦波浪线缓慢流动。
 * - 随机：数量/颜色/基线/振幅/频率/相位圈数/方向/速度都随机，两台设备不会雷同。
 * - 无缝：每条波相位按整周期(cycles 为整数)推进，flow 0→1 循环首尾一致，不跳动。
 * - 速度按屏宽归一化：越宽的屏 duration 越长，抵消"同样相位在宽屏像素位移更大"，
 *   使手机与电视的观感速度一致，且整体偏慢、不打扰。
 */
@Composable
private fun TechBackground() {
    val cfg = LocalConfiguration.current
    val speedScale = (cfg.screenWidthDp / 393f).coerceAtLeast(1f)  // 参考宽度≈主流手机
    val waves = remember { randomWaves() }
    val t = rememberInfiniteTransition(label = "bg")
    val flows = waves.map { w ->
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                tween((w.seconds * 1000f * speedScale).toInt(), easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = w.label
        )
    }
    val twoPi = 6.2831855f
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 不再画渐变底色：外层 Box 已是纯黑主题背景，省电且少一层 overdraw
        val step = w / 60f
        waves.forEachIndexed { i, wv ->
            val flow = flows[i].value
            val baseY = h * wv.baseFrac
            val amp = h * wv.ampFrac
            val k = twoPi * wv.freq / w
            val phase = flow * twoPi * wv.cycles * wv.dir
            val path = Path()
            var x = 0f
            path.moveTo(0f, baseY + amp * sin(phase))
            while (x <= w) {
                path.lineTo(x, baseY + amp * sin(k * x + phase))
                x += step
            }
            // 宽描边做柔光，细描边做主线
            drawPath(path, wv.color.copy(alpha = 0.10f), style = Stroke(width = wv.dp * 3f, cap = StrokeCap.Round))
            drawPath(path, wv.color.copy(alpha = 0.50f), style = Stroke(width = wv.dp, cap = StrokeCap.Round))
        }
    }
}

private class WaveConf(
    val label: String,
    val color: Color,
    val baseFrac: Float,
    val ampFrac: Float,
    val freq: Float,
    val cycles: Int,
    val dir: Int,
    val seconds: Float,
    val dp: Float
)

private val wavePalette = listOf(
    Color(0xFF4CC2A5), Color(0xFF38D9C9), Color(0xFF4CA8E0),
    Color(0xFF5B8DEF), Color(0xFF9B6DFF), Color(0xFFB07BFF)
)

private fun randomWaves(): List<WaveConf> {
    val r = kotlin.random.Random
    val count = 3 + r.nextInt(3)  // 3–5 条
    val colors = wavePalette.shuffled(r).take(count)
    return (0 until count).map { i ->
        WaveConf(
            label = "w$i",
            color = colors[i],
            baseFrac = 0.34f + (0.40f * (i + r.nextFloat() * 0.6f)) / count,  // 在 0.34–0.74 间铺开
            ampFrac = 0.035f + r.nextFloat() * 0.055f,
            freq = 1.0f + r.nextFloat() * 1.8f,
            cycles = 1 + r.nextInt(3),          // 1–3 圈，整数保证无缝
            dir = if (r.nextBoolean()) 1 else -1,
            seconds = 26f + r.nextFloat() * 22f, // 26–48s，慢
            dp = 1.2f + r.nextFloat() * 1.6f
        )
    }
}

private fun paramsFontMultiplier(): Float =
    Params.get("fontScale").toFloatOrNull()?.coerceIn(0.8f, 2.0f) ?: 1f

internal fun permLabel(p: String) = when (p) {
    "render" -> "投送"
    "message" -> "增量"
    "param" -> "参数"
    "screen" -> "屏幕"
    "system" -> "系统"
    "screenshot" -> "截屏"
    "eval" -> "JS"
    else -> p
}
