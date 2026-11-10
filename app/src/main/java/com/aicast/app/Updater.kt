package com.aicast.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 一次可安装的版本描述。字段与官网 `update.json` 一一对应（见 README「更新」）。
 *
 * 为什么清单里要有 `sha256` 和 `versionCode`：安装器只看包名 + 签名，
 * **不校验内容**；下载链路一旦被中间人替换成「同签名的旧包」或半截文件，
 * 安装器照样会装。所以下载完成后先在本地比这两项，再拉起安装器。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val apkUrlFallback: String?,
    val size: Long,
    val sha256: String?,
    val notes: String?,
)

/**
 * 应用自更新：查清单 → 下载 → 校验 → 交给系统安装器。
 *
 * **硬边界**：本 app 不是系统应用，也没有设备管理员权限，所以「静默安装」不存在。
 * 能做的只有把用户点「安装」那一步之外的摩擦降到最低；安装器一定会弹出来。
 */
object Updater {

    /**
     * 清单来源，按顺序都试一遍，**取 versionCode 最大的那份**（不是第一个成功的）。
     *
     * 这样官网清单万一没跟上（手工发布漏了一步），也不会把用户锁死在旧版本上；
     * 反之 GitHub 在国内经常连不上时，官网那份照样能救。
     */
    val MANIFEST_URLS = listOf(
        "https://aicast.lvchen.fun/update.json",
        "https://github.com/hamoyy/AiCast/releases/latest/download/update.json",
    )

    private const val TIMEOUT_MS = 5000
    private const val APK_NAME = "aicast-update.apk"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun currentVersionCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) { 0 }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /**
     * 查所有清单源。
     * @return 需要更新时返回清单；`null` 表示已是最新。
     * @throws Exception 所有源都拿不到（网络问题），由调用方决定是否提示。
     */
    suspend fun check(ctx: Context): UpdateManifest? = withContext(Dispatchers.IO) {
        val found = ArrayList<UpdateManifest>()
        var lastError: Exception? = null
        for (u in MANIFEST_URLS) {
            try {
                fetchOne(u)?.let { found.add(it) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (found.isEmpty()) {
            // 一个清单都没拿到：要么还没发过带清单的版本（404，属正常），
            // 要么网络全挂。只有出现真正的网络异常才报错，别把「还没发版」演成故障。
            if (lastError != null) throw lastError
            return@withContext null
        }
        val newest = found.maxByOrNull { it.versionCode }!!
        if (newest.versionCode > currentVersionCode(ctx)) newest else null
    }

    private fun fetchOne(url: String): UpdateManifest? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            // 清单必须拿最新的：CDN 缓存住旧清单等于「永远提示没有新版」
            setRequestProperty("Cache-Control", "no-cache")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parseManifest(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseManifest(text: String): UpdateManifest? = try {
        val o: JsonObject = json.parseToJsonElement(text).jsonObject
        val code = o["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
        val apk = o["apkUrl"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        UpdateManifest(
            versionCode = code,
            versionName = o["versionName"]?.jsonPrimitive?.contentOrNullSafe() ?: code.toString(),
            minSupportedVersionCode = o["minSupportedVersionCode"]?.jsonPrimitive?.intOrNull ?: 0,
            apkUrl = apk,
            apkUrlFallback = o["apkUrlFallback"]?.jsonPrimitive?.contentOrNullSafe(),
            size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            sha256 = o["sha256"]?.jsonPrimitive?.contentOrNullSafe(),
            notes = o["notes"]?.jsonPrimitive?.contentOrNullSafe(),
        )
    } catch (e: Exception) { null }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this.toString() == "null") null else content.ifBlank { null }

    /** 强制更新：当前版本已经低于清单声明的最低可支持版本（此时弹窗不给「以后再说」） */
    fun mandatory(manifest: UpdateManifest, currentCode: Int): Boolean =
        currentCode < manifest.minSupportedVersionCode

    /**
     * 下载 APK 到 cacheDir。先试主源，失败再试备用源。
     * @param onProgress 0..100
     */
    suspend fun download(
        ctx: Context,
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val sources = listOfNotNull(manifest.apkUrl, manifest.apkUrlFallback).distinct()
        var lastError: Exception? = null
        for (src in sources) {
            try {
                return@withContext downloadFrom(ctx, src, manifest, onProgress)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw (lastError ?: Exception("download failed"))
    }

    private fun downloadFrom(
        ctx: Context,
        url: String,
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
    ): File {
        val out = File(ctx.cacheDir, APK_NAME)
        // 上次下到一半的残留会让「已下载字节数」从错误的起点算，直接清掉重下
        if (out.exists()) out.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            // 已知大小时才能算百分比；服务端没给就退化成「不确定进度」
            val total = if (manifest.size > 0) manifest.size else conn.contentLength.toLong()
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (manifest.size > 0 && read != manifest.size) {
                throw Exception("size mismatch: $read != ${manifest.size}")
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 内容校验：清单给了 sha256 就必须对得上 */
    fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true // 老清单没有该字段时只靠签名把关
        return sha256(file).equals(expected.trim(), ignoreCase = true)
    }

    /**
     * 签名校验：新包的签名必须与当前应用一致，否则系统安装器会以
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝，而且用户看到一个看不懂的错误。
     * 提前判掉，给一句人话。
     */
    fun signatureMatches(ctx: Context, apk: File): Boolean = try {
        @Suppress("DEPRECATION")
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val self = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        val a = incoming?.signatures?.firstOrNull()?.toByteArray()
        val b = self?.signatures?.firstOrNull()?.toByteArray()
        a != null && b != null && a.contentEquals(b)
    } catch (e: Exception) { false }

    /** 是否已允许「安装未知应用」（API 26+ 需要；24/25 只有全局开关，没有这项） */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true

    fun openInstallPermissionSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 个别 ROM 没有这个页面，退到应用详情页（用户仍能找到「安装未知应用」）
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 把网络异常翻成人话：用户/电视上不需要看到 UnknownHostException 这种词 */
    fun describeError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "连不上更新服务器（网络或 DNS 不通）"
        is java.net.SocketTimeoutException -> "连接更新服务器超时"
        is javax.net.ssl.SSLException -> "与更新服务器的连接不安全（证书问题）"
        else -> e.message ?: "更新失败"
    }

    /** 拉起系统安装器。装完系统会杀进程，数据（历史/deviceId/主题）都保留。 */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
