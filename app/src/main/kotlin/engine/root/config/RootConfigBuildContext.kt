// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import app.AppState
import app.effectiveFakeIpEnabled
import app.effectiveLocalDnsEnabled
import app.modes.RunModeEbpf
import app.modes.RunModeTun
import app.modes.isRootRunMode
import system.AndroidNetworkInterfaceProvider
import system.AndroidRootShellGateway
import engine.network.parseCidrAddressOrNull
import engine.network.toPortOrNull
import engine.proxy.ProxyEngineStartRequest
import engine.singbox.DefaultSingBoxDnsFakeIpRange
import engine.singbox.SingBoxConfigFactory
import engine.singbox.prepareSingBoxCoreLogPaths
import features.resources.runtime.SingBoxResourceFilePaths
import features.resources.runtime.singBoxResourceFilePaths
import java.io.File

internal class RootConfigBuildContext(
    private val androidContext: Context,
    val appState: AppState,
    val resourceFilePaths: SingBoxResourceFilePaths,
) {
    fun buildRootStartConfig(): RootStartConfig {
        return appState.toRootStartConfig(
            singBoxConfigBytes = SingBoxConfigFactory.buildConfigBytes(androidContext, appState),
            publicationStagingDirectory = androidContext.cacheDir.absolutePath,
            resourceFilePaths = resourceFilePaths,
        )
    }

    fun buildRootIptablesConfig(): RootIptablesConfig {
        return RootIptablesConfig().withAppSettings(context = androidContext, appState = appState)
    }
}

internal suspend fun Context.prepareRootConfigBuildContext(request: ProxyEngineStartRequest): RootConfigBuildContext {
    applicationContext.prepareSingBoxCoreLogPaths()
    return RootConfigBuildContext(
        androidContext = applicationContext,
        appState = request.appState.withAutoHotspotSharing(),
        resourceFilePaths = singBoxResourceFilePaths(),
    )
}

/**
 * 热点共享自动适配（仅 ROOT 路径会走到；VPN 路径不经过这里）：
 *  - 前缀模式（TPROXY/TUN2SOCKS/BPF2SOCKS）：静态候选前缀并入手填表，覆盖后开的热点；
 *  - 精确名模式（TUN/EBPF）：热点家族候选名整表预填（启动时允许不存在）+ 实测接口并入
 *    ——内核按名字在网卡事件时动态挂载（TUN InterfaceMonitor / EBPF networkMonitor
 *    回调重入 reconcile），热点后开无需重启代理即可接管；探测失败静默回退手填表。
 * 手填表始终保留（并集语义）；归并结果只作用于本次启动的配置副本，不回写持久层。
 */
private suspend fun AppState.withAutoHotspotSharing(): AppState {
    if (!enableHotspotAutoShare || !runMode.isRootRunMode()) return this
    return when (runMode) {
        RunModeTun, RunModeEbpf -> {
            val live = runCatching {
                AndroidNetworkInterfaceProvider(AndroidRootShellGateway()).listNetworkInterfaces()
            }.getOrDefault(emptyList())
            if (runMode == RunModeTun) {
                copy(tunSharedNetworkInterfaces = RootHotspotAutoShare.exactInterfaces(tunSharedNetworkInterfaces, live))
            } else {
                copy(ebpfSharedNetworkInterfaces = RootHotspotAutoShare.exactInterfaces(ebpfSharedNetworkInterfaces, live))
            }
        }
        else -> copy(externalInterfaces = RootHotspotAutoShare.prefixInterfaces(externalInterfaces))
    }
}

private fun AppState.toRootStartConfig(
    singBoxConfigBytes: ByteArray,
    publicationStagingDirectory: String,
    resourceFilePaths: SingBoxResourceFilePaths,
): RootStartConfig {
    val dataDirectory = File(resourceFilePaths.dataDir)
    return RootStartConfig(
        singBoxConfigBytes = singBoxConfigBytes,
        publicationStagingDirectory = publicationStagingDirectory,
        runtimePaths = RootConfigRuntimePaths(
            coreExecutablePath = resourceFilePaths.singBoxCorePath,
            coreConfigPath = File(dataDirectory, "config.json").absolutePath,
            matcherExecutablePath = resourceFilePaths.bpfMatcherPath,
            bpf2SocksExecutablePath = resourceFilePaths.bpf2socksPath,
            hevSocks5TunnelExecutablePath = resourceFilePaths.hevSocks5TunnelPath,
            workingDirectory = resourceFilePaths.dataDir,
            statePath = File(dataDirectory, "asteriskd.state").absolutePath,
            logPath = File(File(dataDirectory, "logs"), "asteriskd.log").absolutePath,
        ),
        directCidrIpv4Path = resourceFilePaths.directCidrIpv4Path,
        directCidrIpv6Path = resourceFilePaths.directCidrIpv6Path,
        enableIpv6 = enableIpv6,
        enableRootIpv6Disabler = enableRootIpv6Disabler,
        enableLocalDns = effectiveLocalDnsEnabled,
        enableFakeIp = effectiveFakeIpEnabled,
        fakeIpIpv4Pool = rootFakeIpIpv4Pool(),
        enableBoot = enableRootBootScript,
        serviceControl = serviceControl,
    )
}

internal fun AppState.tun2SocksInternalProxyPortValue(): Int {
    return socks5ProxyPort.toPortOrNull() ?: DefaultRootTun2SocksProxyPort
}

internal fun AppState.bpf2SocksBridgePortValue(): Int {
    return bpf2SocksBridgePort.toPortOrNull() ?: RootBpf2SocksDefaultBridgePort
}

private fun AppState.rootFakeIpIpv4Pool(): String {
    return dnsServers
        .firstOrNull { server -> server.type == "fakeip" }
        ?.inet4Range
        ?.normalizedIpv4CidrOrNull()
        ?: DefaultSingBoxDnsFakeIpRange.normalizedIpv4CidrOrNull()
        ?: "198.18.0.0/16"
}

private fun String.normalizedIpv4CidrOrNull(): String? {
    val cidr = parseCidrAddressOrNull(this) ?: return null
    if (":" in cidr.address) return null
    val octets = cidr.address.split(".")
    if (octets.size != 4) return null
    var addressValue = 0L
    octets.forEach { octet ->
        val value = octet.toIntOrNull() ?: return null
        addressValue = (addressValue shl 8) or value.toLong()
    }
    val mask = if (cidr.prefixLength == 0) 0L else (0xffffffffL shl (32 - cidr.prefixLength)) and 0xffffffffL
    val network = addressValue and mask
    val address = listOf(
        (network shr 24) and 0xff,
        (network shr 16) and 0xff,
        (network shr 8) and 0xff,
        network and 0xff,
    ).joinToString(".")
    return "$address/${cidr.prefixLength}"
}
