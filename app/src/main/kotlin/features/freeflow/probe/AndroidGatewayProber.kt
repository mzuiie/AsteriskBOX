// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import features.freeflow.domain.GatewayProbeEntry
import features.freeflow.domain.GatewayProfile
import features.freeflow.domain.GatewayProbeKey
import features.freeflow.domain.GatewayVerdict
import features.freeflow.domain.HandshakeSpec
import features.freeflow.domain.computeGatewayVerdict
import features.freeflow.domain.connectRequestLine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网关能力探针（5 组最小 CONNECT，覆盖跳点瓶颈 P3 的策略上限决定变量）。
 * 探测绑定物理网卡直连网关（排除 TRANSPORT_VPN），结论只进档案/建议，不改配置。
 * 报文构造消费通道节点的 HandshakeSpec——探测所见即通道所得。
 */
class AndroidGatewayProber(context: Context?) {

    private val appContext = context?.applicationContext

    suspend fun probe(server: String, port: Int, handshake: HandshakeSpec): GatewayProfile =
        withContext(Dispatchers.IO) {
            val probes = GatewayProbeKey.entries.associate { key ->
                val target = when (key) {
                    GatewayProbeKey.BaiduDomain -> "www.baidu.com:443"
                    GatewayProbeKey.ExternalDomain -> "www.qq.com:443"
                    GatewayProbeKey.RawIp -> "223.5.5.5:443"
                    GatewayProbeKey.DomesticDomain -> "www.jd.com:443"
                    GatewayProbeKey.PlainHttp80 -> "www.baidu.com:80"
                }
                key.name to GatewayProbeEntry(target = target, status = probeTarget(server, port, handshake, target))
            }
            GatewayProfile(
                server = "$server:$port",
                atMillis = System.currentTimeMillis(),
                probes = probes,
                verdict = computeGatewayVerdict { key -> probes[key.name]?.status },
            )
        }

    private fun probeTarget(server: String, port: Int, handshake: HandshakeSpec, target: String): String = try {
        physicalSocket().use { socket ->
            socket.connect(InetSocketAddress(server, port), ConnectTimeoutMillis)
            socket.soTimeout = ConnectTimeoutMillis
            socket.getOutputStream().write(handshake.connectRequestLine(target).toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: return "empty"
            Regex("HTTP/[\\d.]+ (\\d{3})").find(statusLine)?.groupValues?.getOrNull(1)
                ?: statusLine.take(32)
        }
    } catch (error: SocketTimeoutException) {
        "timeout"
    } catch (error: Throwable) {
        "error"
    }

    /** 绑物理网卡：VPN 开启时 App 自身 Socket 默认骑隧道，探测结论会失真。 */
    private fun physicalSocket(): Socket = runCatching {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        val network = manager.allNetworks.firstOrNull { net ->
            val caps = manager.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        network?.socketFactory?.createSocket()
    }.getOrNull() ?: Socket()

    private companion object {
        const val ConnectTimeoutMillis = 5000
    }
}
