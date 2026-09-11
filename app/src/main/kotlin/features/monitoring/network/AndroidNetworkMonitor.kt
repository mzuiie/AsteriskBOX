// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.monitoring.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

internal class AndroidNetworkMonitor(context: Context) {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun snapshot(): LocalNetworkSnapshot {
        return connectivityManager.readLocalNetworkSnapshot(connectivityManager.allNetworks.toList())
    }

    fun snapshots(): Flow<LocalNetworkSnapshot> = callbackFlow {
        val knownNetworks = mutableSetOf<Network>()
        connectivityManager.activeNetwork?.let(knownNetworks::add)

        fun publish() {
            val networks = synchronized(knownNetworks) { knownNetworks.toList() }
            trySend(connectivityManager.readLocalNetworkSnapshot(networks))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(knownNetworks) { knownNetworks += network }
                publish()
            }

            override fun onLost(network: Network) {
                synchronized(knownNetworks) { knownNetworks -= network }
                publish()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                synchronized(knownNetworks) { knownNetworks += network }
                publish()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                synchronized(knownNetworks) { knownNetworks += network }
                publish()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
            .onFailure { close(it) }
        publish()
        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.conflate()
}

internal class PublicNetworkProbeClient(
    private val context: Context? = null,
    private val endpoints: List<PublicProbeEndpoint> = DefaultPublicProbeEndpoints,
) {
    suspend fun probe(): Pair<PublicProbeAttempt, PublicProbeAttempt> = coroutineScope {
        val ipv4 = async { probeFamily(AddressFamily.Ipv4) }
        val ipv6 = async { probeFamily(AddressFamily.Ipv6) }
        ipv4.await() to ipv6.await()
    }

    suspend fun probe(family: AddressFamily): PublicProbeAttempt {
        return probeFamily(family)
    }

    /** 同族端点按序轮试，取第一个成功结果。 */
    private suspend fun probeFamily(family: AddressFamily): PublicProbeAttempt {
        var last: PublicProbeAttempt = PublicProbeAttempt.Failure(
            error = PublicProbeError.Network,
            message = "No probe endpoint",
            endpointHost = "",
        )
        endpoints.filter { endpoint -> endpoint.family == family }.forEach { endpoint ->
            val attempt = probeOne(endpoint)
            if (attempt is PublicProbeAttempt.Success) return attempt
            last = attempt
        }
        return last
    }

    private suspend fun probeOne(endpoint: PublicProbeEndpoint): PublicProbeAttempt {
        return try {
            withTimeout(PublicProbeOverallTimeoutMillis.milliseconds) {
                val tunneled = executeRequest(endpoint)
                // 默认路径（App 自身流量随 VPN 进隧道）失败时，绑物理网卡直连兜底：
                // 隧道出口被网关拒绝时公网地址页仍有可用结果，且能区分出口来源
                if (tunneled is PublicProbeAttempt.Failure) {
                    directNetwork()?.let { network ->
                        executeRequest(endpoint, network = network, viaDirect = true)
                    } ?: tunneled
                } else {
                    tunneled
                }
            }
        } catch (_: TimeoutCancellationException) {
            PublicProbeAttempt.Failure(
                error = PublicProbeError.Timeout,
                message = "Timed out",
                endpointHost = endpoint.host,
            )
        }
    }

    /** 物理可上网网卡（排除 VPN 传输），用于直连兜底探测。 */
    private fun directNetwork(): Network? {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val capabilities = cm.getNetworkCapabilities(network)
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private suspend fun executeRequest(
        endpoint: PublicProbeEndpoint,
        network: Network? = null,
        viaDirect: Boolean = false,
    ): PublicProbeAttempt {
        return suspendCancellableCoroutine { continuation ->
            val connectionReference = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation {
                connectionReference.getAndSet(null)?.disconnect()
            }
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                if (!continuation.isActive) return@dispatch
                val startedAt = SystemClock.elapsedRealtime()
                val attempt = runCatching {
                    val url = URI(endpoint.url).toURL()
                    val connection = (
                        if (network != null) {
                            network.openConnection(url)
                        } else {
                            url.openConnection()
                        }
                        ) as HttpURLConnection
                    connection.apply {
                        requestMethod = "GET"
                        connectTimeout = PublicProbeSocketTimeoutMillis
                        readTimeout = PublicProbeSocketTimeoutMillis
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("Accept", "application/json, text/plain")
                        setRequestProperty("User-Agent", "AsteriskBOX")
                    }
                    connectionReference.set(connection)
                    if (!continuation.isActive) {
                        connection.disconnect()
                        return@runCatching PublicProbeAttempt.Failure(
                            PublicProbeError.Network,
                            "Cancelled",
                            endpoint.host,
                        )
                    }
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("HTTP $status")
                        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readLimited(PublicProbeMaxResponseChars)
                        }
                        val address = parsePublicAddressResponse(body, endpoint.family)
                            ?: return@runCatching PublicProbeAttempt.Failure(
                                PublicProbeError.InvalidResponse,
                                "Invalid ${endpoint.family.name.uppercase()} address",
                                endpoint.host,
                            )
                        PublicProbeAttempt.Success(
                            address = address,
                            durationMillis = SystemClock.elapsedRealtime() - startedAt,
                            endpointHost = endpoint.host,
                            viaDirect = viaDirect,
                        )
                    } finally {
                        connectionReference.compareAndSet(connection, null)
                        connection.disconnect()
                    }
                }.getOrElse { error ->
                    PublicProbeAttempt.Failure(
                        error = PublicProbeError.Network,
                        message = error.message?.take(160).orEmpty().ifBlank { "Request failed" },
                        endpointHost = endpoint.host,
                    )
                }
                if (continuation.isActive) continuation.resume(attempt)
            }
        }
    }
}

private fun ConnectivityManager.readLocalNetworkSnapshot(networks: List<Network>): LocalNetworkSnapshot {
    val selected = networks
        .mapNotNull { network ->
            val capabilities = getNetworkCapabilities(network) ?: return@mapNotNull null
            val linkProperties = getLinkProperties(network) ?: return@mapNotNull null
            NetworkCandidate(
                network = network,
                capabilities = capabilities,
                linkProperties = linkProperties,
            )
        }
        .filter { candidate ->
            candidate.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        .maxWithOrNull(
            compareBy<NetworkCandidate> { candidate ->
                !candidate.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }.thenBy { candidate ->
                candidate.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenBy { candidate ->
                candidate.network == activeNetwork
            },
        )
        ?: return LocalNetworkSnapshot(updatedAtMillis = System.currentTimeMillis())
    val capabilities = selected.capabilities
    val linkProperties = selected.linkProperties
    val addresses = linkProperties.linkAddresses
        .mapNotNull { linkAddress -> linkAddress.address.toLocalAddressOrNull() }
        .distinct()
    return LocalNetworkSnapshot(
        transport = capabilities.toNetworkTransport(),
        networkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        internetValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        interfaceName = linkProperties.interfaceName.orEmpty(),
        ipv4Addresses = addresses.filter { address -> address.contains('.') },
        ipv6Addresses = addresses.filter { address -> address.contains(':') },
        gateways = linkProperties.routes
            .asSequence()
            .filter { route -> route.isDefaultRoute }
            .mapNotNull { route -> route.gateway?.toLocalAddressOrNull() }
            .distinct()
            .toList(),
        dnsServers = linkProperties.dnsServers.mapNotNull(InetAddress::toLocalAddressOrNull).distinct(),
        updatedAtMillis = System.currentTimeMillis(),
    )
}

private fun NetworkCapabilities.toNetworkTransport(): NetworkTransport {
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.Bluetooth
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.Vpn
        else -> NetworkTransport.Other
    }
}

private fun InetAddress.toLocalAddressOrNull(): String? {
    if (this !is Inet4Address && this !is Inet6Address) return null
    if (isAnyLocalAddress || isLoopbackAddress || isMulticastAddress) return null
    return hostAddress?.substringBefore('%')?.takeIf(String::isNotBlank)
}

private fun java.io.Reader.readLimited(maxChars: Int): String {
    val result = StringBuilder(maxChars.coerceAtMost(256))
    val buffer = CharArray(256)
    while (result.length <= maxChars) {
        val count = read(buffer, 0, minOf(buffer.size, maxChars + 1 - result.length))
        if (count < 0) break
        result.appendRange(buffer, 0, count)
    }
    if (result.length > maxChars) error("Response too large")
    return result.toString()
}

private data class NetworkCandidate(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties,
)

private const val PublicProbeSocketTimeoutMillis = 5_000
private const val PublicProbeOverallTimeoutMillis = 5_000L
private const val PublicProbeMaxResponseChars = 8_192
