package com.aicast.app

import android.content.Context
import android.os.Build
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 4.4 上的 HTTPS 兜底（只影响应用内更新这条链路）。
 *
 * 4.4 有两个绕不过去的问题：
 * 1. `SSLSocket` 默认**不启用 TLS 1.2**，握手会协商到 TLS 1.0，遇到只支持 1.2 的站点直接失败；
 * 2. 系统信任库是 2014 年的，**没有 ISRG Root X1**（Let's Encrypt 现在的根）。
 *
 * 这里不改系统，只为本进程换一个 `SSLSocketFactory`：
 * - 协议固定成 TLSv1.2（这正是 `SSLContext.getInstance("TLSv1.2")` 的作用）；
 * - 信任链 = 系统默认 CA + 随包内置的 ISRG Root X1（`res/raw/isrgrootx1.pem`）。
 *
 * API 21+ 完全不动（系统已经默认 1.2 且信任库够新）。
 */
object TlsCompat {

    private var installed = false

    fun install(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 21) return
        synchronized(this) {
            if (installed) return
            installed = true
            runCatching {
                val extra = extraTrustManager(ctx) ?: return@runCatching
                // 必须合成一个：SSLContext 只会用第一个 X509TrustManager 做服务端校验，
                // 传两个等于把系统 CA 丢掉
                val composite = CompositeTrustManager(defaultTrustManagers() + extra)
                val ssl = SSLContext.getInstance("TLSv1.2")
                ssl.init(null, arrayOf<TrustManager>(composite), SecureRandom())
                HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            }
        }
    }

    private fun defaultTrustManagers(): List<X509TrustManager> = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>()
    } catch (e: Exception) { emptyList() }

    /** 把随包的 ISRG Root X1 装进一个只有它自己的信任库 */
    private fun extraTrustManager(ctx: Context): X509TrustManager? = try {
        val cert = ctx.resources.openRawResource(R.raw.isrgrootx1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isrg-root-x1", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) { null }

    /** 依次问每个信任库：任一通过即通过 */
    private class CompositeTrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (d in delegates) {
                try { d.checkClientTrusted(chain, authType); return }
                catch (e: CertificateException) { last = e }
            }
            throw last ?: CertificateException("no trusted certificate path")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (d in delegates) {
                try { d.checkServerTrusted(chain, authType); return }
                catch (e: CertificateException) { last = e }
            }
            throw last ?: CertificateException("no trusted certificate path")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}
