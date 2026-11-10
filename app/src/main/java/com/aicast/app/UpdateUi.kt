package com.aicast.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val updCard = Color(0xFF151A21)
private val updText = Color(0xFFE6EAF0)
private val updDim = Color(0xFF8A939E)
private val updFocusBg = Color(0xFF20262F)
private val updBtnBg = Color(0xFF232A33)
private val updWarn = Color(0xFFE5B567)

/** 更新流程的状态机。`Idle` 表示「什么都没发生」，不显示任何界面。 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object Latest : UpdateState()
    data class Available(val manifest: UpdateManifest, val mandatory: Boolean) : UpdateState()
    data class Downloading(val manifest: UpdateManifest, val progress: Int) : UpdateState()
    data class Failed(val manifest: UpdateManifest?, val message: String) : UpdateState()
    data class NeedsPermission(val manifest: UpdateManifest) : UpdateState()
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * 更新流程控制器：**所有更新相关 UI 都从这里读状态**。
 *
 * 状态集中在一处，是为了避免「设置页显示正在下载、弹窗也显示正在下载」两个进度对不上；
 * 也让「同版本只弹一次」这类规则只有一个判定点。
 */
class UpdateController(private val ctx: Context, private val scope: CoroutineScope) {

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    /** 弹窗是否显示。与 [state] 分开：下载失败后仍希望弹窗留着展示原因 */
    var dialogVisible by mutableStateOf(false)
        private set

    private fun runCheck(automatic: Boolean, onDone: ((String) -> Unit)? = null) {
        if (automatic) {
            val now = System.currentTimeMillis()
            if (now - Prefs.updateLastCheckAt < DAY_MS) return
            Prefs.updateLastCheckAt = now
        } else {
            state = UpdateState.Checking
        }
        scope.launch {
            try {
                val m = Updater.check(ctx)
                if (m == null) {
                    // 自动检查时静默：用户没问，不必告诉他「已经是最新」
                    if (!automatic) state = UpdateState.Latest
                    else state = UpdateState.Idle
                    onDone?.invoke("")
                    return@launch
                }
                val mandatory = Updater.mandatory(m, Updater.currentVersionCode(ctx))
                val declined = m.versionCode == Prefs.updateDeclinedVersion
                if (!automatic || mandatory || !declined) {
                    state = UpdateState.Available(m, mandatory)
                    dialogVisible = true
                }
            } catch (e: Exception) {
                // 网络问题不打扰用户：自动检查完全静默，手动检查给一句人话
                state = UpdateState.Idle
                if (!automatic) onDone?.invoke(Updater.describeError(e))
            }
        }
    }

    /** 应用打开时调用：一天最多一次，失败静默 */
    fun checkAutomatically() = runCheck(automatic = true)

    /** 设置页「检查更新」：不受节流与「以后再说」限制 */
    fun checkManually(onDone: (String) -> Unit) = runCheck(automatic = false, onDone = onDone)

    /** 用户点「以后再说」：只对当前版本静默，下个版本仍会提示 */
    fun dismissDialog() {
        (state as? UpdateState.Available)?.let {
            Prefs.updateDeclinedVersion = it.manifest.versionCode
        }
        dialogVisible = false
        if (state is UpdateState.Available || state is UpdateState.Failed) state = UpdateState.Idle
    }

    fun startUpdate(manifest: UpdateManifest) {
        if (!Updater.canInstall(ctx)) {
            state = UpdateState.NeedsPermission(manifest)
            dialogVisible = true
            return
        }
        state = UpdateState.Downloading(manifest, 0)
        dialogVisible = true
        scope.launch {
            try {
                val file = Updater.download(ctx, manifest) { pct ->
                    // 进度回调在 IO 线程：切回主线程改状态，避免跨线程写 Compose 状态
                    scope.launch(Dispatchers.Main) {
                        if (state is UpdateState.Downloading) {
                            state = UpdateState.Downloading(manifest, pct)
                        }
                    }
                }
                if (!Updater.verifySha256(file, manifest.sha256)) {
                    file.delete()
                    state = UpdateState.Failed(manifest, "下载的文件校验不通过（sha256 不符），已丢弃")
                    return@launch
                }
                if (!Updater.signatureMatches(ctx, file)) {
                    file.delete()
                    state = UpdateState.Failed(manifest, "包的签名与当前应用不一致，装不上；请到官网手动下载")
                    return@launch
                }
                state = UpdateState.Idle
                dialogVisible = false
                Updater.install(ctx, file)
            } catch (e: Exception) {
                state = UpdateState.Failed(manifest, Updater.describeError(e))
            }
        }
    }

    fun openPermission(manifest: UpdateManifest) {
        Updater.openInstallPermissionSettings(ctx)
        state = UpdateState.Idle
        dialogVisible = false
    }

    /** 权限页回来后再点一次「检查/更新」即可继续；这里只把状态复位 */
    fun reset() { state = UpdateState.Idle }
}

/**
 * 更新弹窗。
 *
 * 电视端两条铁律：**默认焦点放「以后再说」**（打扰性、非用户主动发起的操作，默认取消）；
 * 按钮整块是焦点目标且聚焦时整块变亮 + accent 描边——几米外只亮一小圈等于没有焦点。
 */
@Composable
fun UpdateDialog(controller: UpdateController) {
    if (!controller.dialogVisible) return
    val ctx = LocalContext.current
    val state = controller.state
    val manifest = when (state) {
        is UpdateState.Available -> state.manifest
        is UpdateState.Downloading -> state.manifest
        is UpdateState.NeedsPermission -> state.manifest
        is UpdateState.Failed -> state.manifest
        else -> null
    } ?: return
    val mandatory = (state as? UpdateState.Available)?.mandatory ?: false
    val current = remember { Updater.currentVersionName(ctx) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(24.dp)
                .background(updCard, RoundedCornerShape(16.dp))
                .padding(22.dp)
        ) {
            Text(
                stringResource(R.string.update_title, manifest.versionName),
                color = updText, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.update_from_to, current, manifest.versionName),
                color = updDim, fontSize = 13.sp
            )

            manifest.notes?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(it.take(1200), color = updText, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }

            when (state) {
                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_downloading, state.progress),
                        color = updDim, fontSize = 13.sp
                    )
                }
                is UpdateState.NeedsPermission -> {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.update_need_permission), color = updWarn, fontSize = 13.sp)
                }
                is UpdateState.Failed -> {
                    Spacer(Modifier.height(14.dp))
                    Text(state.message, color = Color(0xFFE58C8C), fontSize = 13.sp)
                }
                else -> {}
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state !is UpdateState.Downloading) {
                    DialogButton(
                        text = if (mandatory) stringResource(R.string.update_close)
                        else stringResource(R.string.update_later),
                        primary = false,
                        autoFocus = true,
                    ) { controller.dismissDialog() }
                }
                DialogButton(
                    text = when (state) {
                        is UpdateState.NeedsPermission -> stringResource(R.string.update_open_permission)
                        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
                        is UpdateState.Failed -> stringResource(R.string.update_retry)
                        else -> stringResource(R.string.update_now)
                    },
                    primary = true,
                    autoFocus = false,
                    enabled = state !is UpdateState.Downloading,
                ) {
                    when (state) {
                        is UpdateState.NeedsPermission -> controller.openPermission(state.manifest)
                        is UpdateState.Available, is UpdateState.Failed -> controller.startUpdate(manifest)
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    autoFocus: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 120.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .background(
                when {
                    primary -> Color(0xFF3E7BFA)
                    focused -> updFocusBg
                    else -> updBtnBg
                },
                shape
            )
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) accent else Color.Transparent,
                shape
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) updText else updDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 设置页「关于」区块：当前版本 + 检查更新（手动入口常驻，不受自动弹窗节流影响） */
@Composable
fun AboutSection(controller: UpdateController, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val state = controller.state
    val version = remember { "${Updater.currentVersionName(ctx)} (${Updater.currentVersionCode(ctx)})" }

    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            stringResource(R.string.about_version),
            color = updDim, fontSize = 13.sp,
            modifier = Modifier.widthIn(min = 56.dp)
        )
        Text(version, color = updText, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))

    val label = when (state) {
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Latest -> stringResource(R.string.update_latest)
        is UpdateState.Available -> stringResource(R.string.update_to, state.manifest.versionName)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.NeedsPermission -> stringResource(R.string.update_open_permission)
        is UpdateState.Failed -> stringResource(R.string.update_retry)
        else -> stringResource(R.string.update_check)
    }

    SettingsActionRow(label, enabled = state !is UpdateState.Checking) {
        when (state) {
            is UpdateState.Available -> controller.startUpdate(state.manifest)
            is UpdateState.NeedsPermission -> controller.openPermission(state.manifest)
            is UpdateState.Failed -> controller.reset()
            else -> controller.checkManually { msg -> if (msg.isNotBlank()) onMessage(msg) }
        }
    }
}

/** 与 ToggleRow 同一套焦点口径：整行可聚焦、整行变亮 + accent 描边 */
@Composable
private fun SettingsActionRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) updFocusBg else Color.Transparent, shape)
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) accent else Color.Transparent,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = updText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
