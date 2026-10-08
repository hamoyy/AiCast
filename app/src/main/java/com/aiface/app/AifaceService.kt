package com.aiface.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 前台保活 service：常驻 HTTP server + NSD 广播 + 常亮 wake lock */
class AifaceService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        App.ctx = applicationContext
        Prefs.init(applicationContext)
        startInForeground()
        if (!Server.isRunning() && !Server.start()) {
            android.util.Log.e("aiface", "server 启动失败：${Server.lastError}")
        }
        NsdAdvertiser.advertise(applicationContext)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aiface:keep")
            .apply { setReferenceCounted(false); acquire() }
        Media.prune() // 过期媒体在非写入路径上永远不会被清理，启动时理一次
        startNetWatch()
    }

    /** IP 刷新：网络变化驱动为主，定时兜底（原来是死循环 10s 全量扫网卡，手机端白耗电） */
    private fun startNetWatch() {
        val cm = runCatching {
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull()
        if (cm != null && Build.VERSION.SDK_INT >= 24) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { Net.refresh(); bumpNotification() }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) { Net.refresh(); bumpNotification() }
                override fun onLinkPropertiesChanged(
                    network: android.net.Network,
                    props: android.net.LinkProperties
                ) { Net.refresh(); bumpNotification() }
            }
            netCallback = cb
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }
        scope.launch {
            while (true) {
                Net.refresh()
                bumpNotification()
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉又重建时确保 server 还在（P0-3：Server.start 幂等，不会重复 bind）
        if (!Server.isRunning()) Server.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            netCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        netCallback = null
        NsdAdvertiser.stop()
        Server.stop()   // 真正停掉：以前只把标志位置 false，端口还占着 → 重建时重复绑定崩溃
        wake?.release()
        wake = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "core"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "aiface 运行中", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("aiface")
            .setContentText("${Net.localIp()}:${Server.port}")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    /** IP/端口变了同步通知栏（以前只在 onCreate 取一次，换网就显示错的地址） */
    private var lastNotifText = ""
    private fun bumpNotification() {
        val text = "${Net.localIp()}:${Server.port}"
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notif = NotificationCompat.Builder(this, "core")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("aiface")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(1, notif) }
    }

    companion object {
        fun ensure(ctx: Context) {
            val i = Intent(ctx, AifaceService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }
}

/** 开机自启 + 升级后自恢复（§11，尽力而为） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> AifaceService.ensure(ctx)
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
        }
    }
}
