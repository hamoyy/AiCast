package com.aicast.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 4.4 兼容版的更新流程。
 *
 * 为什么不复用 [UpdateController]：它的状态是 Compose 的 `mutableStateOf`，在 API 19 上
 * 会拉起 compose.runtime 的类。这里用平台 Dialog + 同一个 [Updater] 逻辑重写一遍，
 * 行为（节流、强制更新、sha256/签名校验、跳转授权页）保持一致。
 */
class LegacyUpdate(private val act: Activity) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dialog: AlertDialog? = null
    private var progress: ProgressDialog? = null

    @Volatile var dialogVisible = false
        private set

    // ---------- 入口 ----------

    /** 应用打开后调用：一天最多一次，失败静默（用户没问，不必打扰） */
    fun checkAutomatically() {
        val now = System.currentTimeMillis()
        if (now - Prefs.updateLastCheckAt < DAY_MS) return
        Prefs.updateLastCheckAt = now
        scope.launch {
            try {
                val m = Updater.check(act) ?: return@launch
                // 同一版本用户点过「以后再说」就不再弹；强制更新时不受此限制
                val mandatory = Updater.mandatory(m, Updater.currentVersionCode(act))
                if (mandatory || m.versionCode != Prefs.updateDeclinedVersion) showAvailable(m)
            } catch (e: Exception) { /* 静默 */ }
        }
    }

    /** 设置页「检查更新」：不受节流与「以后再说」限制 */
    fun checkManually(onDone: (String) -> Unit) {
        scope.launch {
            try {
                val m = Updater.check(act)
                if (m == null) {
                    onDone(Str.get(R.string.update_latest))
                } else {
                    onDone("")
                    showAvailable(m)
                }
            } catch (e: Exception) {
                onDone(Updater.describeError(e))
            }
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        dialogVisible = false
    }

    fun dispose() {
        dismiss()
        progress?.dismiss()
        progress = null
        scope.cancel()
    }

    // ---------- 内部 ----------

    private fun showAvailable(m: UpdateManifest) {
        dismiss()
        val mandatory = Updater.mandatory(m, Updater.currentVersionCode(act))
        val body = buildString {
            append(Str.get(R.string.update_from_to, Updater.currentVersionName(act), m.versionName))
            m.notes?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        }
        val b = AlertDialog.Builder(act)
            .setTitle(Str.get(R.string.update_title, m.versionName))
            .setMessage(body)
            .setPositiveButton(Str.get(R.string.update_now)) { _, _ -> download(m) }
        if (!mandatory) {
            b.setNegativeButton(Str.get(R.string.update_later)) { _, _ ->
                Prefs.updateDeclinedVersion = m.versionCode
            }
        }
        val d = b.create()
        d.setCancelable(!mandatory)
        d.setOnDismissListener {
            dialogVisible = false
            dialog = null
        }
        dialogVisible = true
        dialog = d
        d.show()
    }

    @Suppress("DEPRECATION")
    private fun download(m: UpdateManifest) {
        val pd = ProgressDialog(act).apply {
            setMessage(Str.get(R.string.update_checking))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(true)
        }
        progress = pd
        runCatching { pd.show() }
        scope.launch {
            try {
                val file = Updater.download(act, m) { pct ->
                    act.runOnUiThread {
                        pd.progress = pct
                        pd.setMessage(Str.get(R.string.update_downloading, pct))
                    }
                }
                // 安装器只看包名与签名，不校验内容：先自己比 sha256 与签名
                if (!Updater.verifySha256(file, m.sha256)) {
                    pd.dismiss()
                    toast(Str.get(R.string.legacy_verify_failed))
                    return@launch
                }
                if (!Updater.signatureMatches(act, file)) {
                    pd.dismiss()
                    toast(Str.get(R.string.legacy_signature_mismatch))
                    return@launch
                }
                pd.dismiss()
                if (!Updater.canInstall(act)) promptPermission() else Updater.install(act, file)
            } catch (e: Exception) {
                runCatching { pd.dismiss() }
                toast(Updater.describeError(e))
            }
        }
    }

    private fun promptPermission() {
        AlertDialog.Builder(act)
            .setTitle(Str.get(R.string.legacy_update))
            .setMessage(Str.get(R.string.update_need_permission))
            .setPositiveButton(Str.get(R.string.update_open_permission)) { _, _ ->
                Updater.openInstallPermissionSettings(act)
            }
            .setNegativeButton(Str.get(R.string.action_cancel), null)
            .show()
    }

    private fun toast(s: String) {
        if (s.isNotBlank()) Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
