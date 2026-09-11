// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

/**
 * 热点共享自动适配的接口名归并规则（纯函数，JVM 可测）。
 * 候选集取自工作区已验证模块的接口表（clnc-atp2 OTHER_PROXY_INTERFACES、
 * ebpf-baidu 启动探测候选 wlan1/ap0/swlan0/eth0/rndis0/usb0）：
 *  - 前缀模式（TPROXY/TUN2SOCKS/BPF2SOCKS）：iptables -i 通配符不要求接口存在，
 *    静态候选并入手填表即可覆盖后开的热点；
 *  - 精确名模式（TUN/EBPF）：预填候选名整表（允许启动时不存在），内核网卡监视器
 *    按名字在事件时动态挂载，后开热点同样免重启接管；实测枚举补充非标名字。
 */
object RootHotspotAutoShare {

    /** 接入点家族前缀（iptables -i 通配符语义，trailing +）。 */
    val PrefixCandidates = listOf("ap+", "softap+", "swlan+", "rndis+", "usb+", "bnep+", "bt-pan+", "eth+")

    /** WLAN 热点候选精确名（wlan0 通常是 station，不收，华为复用 wlan0 的老机型走手填）。 */
    val WlanApCandidates = listOf("wlan1", "wlan2", "wlan3")

    /**
     * 精确名模式（TUN/EBPF）预填候选：名字在启动时**允许不存在**——TUN 的
     * include_interface 走 InterfaceMonitor、EBPF 的 shared.interface 走
     * networkMonitor 回调，内核都按名字在网卡事件时动态挂载/匹配，后开的热点
     * 无需重启代理即可被接管（时序冲突的根治方案，替代"后开热点需重启"旧语义）。
     */
    val ExactCandidates = listOf(
        "ap0", "ap1", "softap0", "swlan0",
        "wlan1", "wlan2", "wlan3",
        "rndis0", "usb0", "bnep0", "bt-pan0", "eth0",
    )

    private val ExactFamilies = listOf("ap", "softap", "swlan", "rndis", "usb", "bnep", "bt-pan", "eth")

    private val ExcludedExact = setOf("lo", "wlan0")

    /** 前缀模式生效表：手填 ∪ 静态候选（去重）。 */
    fun prefixInterfaces(manual: List<String>): List<String> =
        (manual + PrefixCandidates + WlanApCandidates).distinct()

    /** 精确名模式生效表：手填 ∪ 预填候选 ∪ 实测热点家族接口（去重）。 */
    fun exactInterfaces(manual: List<String>, live: List<String>): List<String> =
        (manual + ExactCandidates + live.filter(::isHotspotInterface)).distinct()

    fun isHotspotInterface(name: String): Boolean =
        name !in ExcludedExact &&
            !name.startsWith("wlan0") &&
            !name.startsWith("asterisk") &&
            (ExactFamilies.any(name::startsWith) || name in WlanApCandidates)
}
