package com.aiface.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** NSD/mDNS 广播 _aiface._tcp，TXT 带 name/class/touch/mode/open（§3） */
object NsdAdvertiser {

    const val SERVICE_TYPE = "_aiface._tcp"
    private const val TAG = "aiface-nsd"
    private var registered = false
    private var listener: NsdManager.RegistrationListener? = null
    private var nsd: NsdManager? = null

    /** 设置变更后重广播（name/mode/open 变了要更新 TXT） */
    fun restart(ctx: Context) {
        val l = listener
        if (l != null) {
            runCatching { nsd?.unregisterService(l) }
            listener = null
            registered = false
        }
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ advertise(ctx) }, 800)
    }

    /** 服务下线时注销广播（此前从不注销，长期残留） */
    fun stop() {
        val l = listener ?: return
        runCatching { nsd?.unregisterService(l) }
        listener = null
        registered = false
    }

    fun advertise(ctx: Context) {
        if (registered) return
        val mgr = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = mgr
        val info = NsdServiceInfo().apply {
            serviceName = Prefs.name.ifBlank { "aiface" }
            serviceType = SERVICE_TYPE
            // 端口可能因被占用而顺延，广播实际监听端口（P0-3）
            port = Server.port
            // setAttribute(String,String) 需 API 33+；低版本仅靠 serviceName 携带名称
            if (Build.VERSION.SDK_INT >= 33) {
                setAttribute("deviceId", Prefs.deviceId)
                setAttribute("class", DeviceInfo.deviceClass())
                setAttribute("touch", if (DeviceInfo.isTouch()) "1" else "0")
                setAttribute("mode", Prefs.mode)
                setAttribute("open", Prefs.openList().joinToString(","))
            }
        }
        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                registered = true
                Log.i(TAG, "registered ${si.serviceName}")
            }

            override fun onRegistrationFailed(si: NsdServiceInfo, op: Int) {
                Log.w(TAG, "register failed: $op")
            }

            override fun onServiceUnregistered(si: NsdServiceInfo) { registered = false }
            override fun onUnregistrationFailed(si: NsdServiceInfo, op: Int) {}
        }
        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener!!)
    }
}
