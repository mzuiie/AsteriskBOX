// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.domain

import kotlinx.serialization.Serializable

/**
 * 免流声明档：免流配置的唯一事实源（Room free_flow_profile 单行 JSON）。
 * UI/用例只改档；编译器把档编译成 sing-box 配置产物并自检领域铁律。
 */
@Serializable
data class FreeFlowProfile(
    val schemaVersion: Int = 1,
    /** 档存在但可整体停用（区别于"移除"：停用保留档，重建零成本）。 */
    val enabled: Boolean = false,
    val channel: ChannelSpec = ChannelSpec(),
    val udp: UdpPolicy = UdpPolicy.Strict,
    val cnsNodes: List<CnsSpec> = emptyList(),
    val airports: List<AirportSpec> = emptyList(),
    val split: SplitSpec = SplitSpec(),
    val guards: GuardSpec = GuardSpec(),
    /** 套用前被免流强制改写的开关原值（移除免流时恢复）。 */
    val restore: RestoreSpec = RestoreSpec(),
)

/** 免流强制改写的开关原值（套用时快照，移除时恢复）。 */
@Serializable
data class RestoreSpec(
    /** ROOT 模式「IPv6 禁用器」原值：免流启用期间强制开（物理禁 v6 宁断不跳），移除时恢复。 */
    val enableRootIpv6Disabler: Boolean = false,
)

@Serializable
data class ChannelSpec(
    /** 族 key（FreeFlowFamily.key）。 */
    val familyKey: String = BaiduT5Family.key,
    /**
     * 通道成员（source 标记货源：编译器按 source 重建；订阅拉取的节点留在
     * AppState 内随编译透传，不被档内重建冲掉）。
     */
    val nodes: List<NodeSpec> = emptyList(),
    /** 网关订阅地址（可选；令牌轮换走订阅，静态内置库只是首拉失败的兜底，见 I8）。 */
    val subscriptionUrl: String = "",
)

@Serializable
enum class NodeSource {
    /** 族内置库（编译器随族重建）。 */
    Builtin,

    /** 订阅拉取（随订阅整组替换）。 */
    Subscription,

    /** 用户手工添加（族切换时保留）。 */
    Manual,
}

/** 通道节点：网关地址 + 握手形态（形态即数据，族识别/校验/探测共用同一份）。 */
@Serializable
data class NodeSpec(
    val remarks: String,
    val server: String,
    val serverPort: Int,
    val handshake: HandshakeSpec = HandshakeSpec(),
    val source: NodeSource = NodeSource.Builtin,
)

/** UDP 策略单枚举：互斥由类型系统表达（Strict=白名单外全拒 / ViaCns=白名单外走 CNS / AllowAll=直连放行）。 */
@Serializable
enum class UdpPolicy {
    Strict,
    ViaCns,
    AllowAll,
}

/**
 * CNS UDP 隧道节点：TCP 会话骑免流通道（prependFree，detour 指通道组）或直连；
 * 与官方 CNS 服务端（mmmdbybyd/CNS）协议互通。CNS 必须独立组（组 selector 互指
 * 会 circular dependency FATAL，见 I12）。
 */
@Serializable
data class CnsSpec(
    val name: String = "",
    val server: String,
    val port: Int,
    val password: String,
    val masking: Boolean = true,
    val maskHost: String = "",
    val prependFree: Boolean = true,

    /** TCP 隧道目标头名（内核缺省 Meng，需与服务端 proxy_key 一致）；=内核默认时不写 JSON。 */
    val proxyKey: String = "Meng",

    /** UDP 会话伪装头识别串（内核缺省 httpUDP，需与服务端 Udp_flag 一致）；=内核默认时不写 JSON。 */
    val udpFlag: String = "httpUDP",
)

/** 机场组（国内外分流用；成员节点由订阅拉取/导入进 AppState，编译时透传保留）。 */
@Serializable
data class AirportSpec(
    val name: String,
    val url: String = "",
)

@Serializable
enum class SplitMode {
    /** 百度直连：国内外全走免流通道（route.final = 通道组）。 */
    FreeOnly,

    /** 百度链式：国内走免流，国外走机场节点、且机场腿 detour 骑免流通道（链式出国免流）。
     *  已知边界：网关上游可能按域名关键词拦截部分国外站点（历史实测 google 系 503）。 */
    Chained,

    /** 百度分流：国内走免流（geosite-cn+geoip-cn 分流），国外直接走机场（不经网关，机场出国同样免流）。 */
    AirportDirect,

    /** 纯机场：全部走机场——国内规则进「机场·国内」组、其余进「机场·国外」组，节点在代理页各选。 */
    AirportOnly,
}

@Serializable
data class SplitSpec(
    val mode: SplitMode = SplitMode.FreeOnly,
    /** 百度链式/百度分流/纯机场生效的机场组名。 */
    val activeAirport: String? = null,
)

/** 农行兼容受管包名（仅实测异常的农业银行；其余银行 App 走代理正常，不纳入）。 */
val AgriBankPackageNames = listOf("com.android.bankabc")

@Serializable
data class GuardSpec(
    /** 游戏 UDP 按包名直连（延迟敏感不走 CNS）。 */
    val gameUdpPackages: List<String> = emptyList(),
    /** 遥测 P2P 本地拦截开关（降噪省通道额度）。 */
    val telemetryBlockEnabled: Boolean = true,

    /** 农行兼容实验（默认关）：农行硬编码 IP:441/442 直连自己的服务器，网关白名单按
     *  IP:端口对放行、这类目标必 403（"服务异常"根因）。开启后其 TCP 改走 CNS 中继
     *  （clnc 同款载体：目标藏协议头、中继拨真实目标，出口国内，免流属性不变）。
     *  软依赖 CNS 节点（无节点开关不起作用；机场/王卡腿经用户否决，根治=服务商加白名单）。
     *  纯机场模式不适用。 */
    val agriBankCompatEnabled: Boolean = false,

    /** QQ/微信放行：微信/QQ 通话 UDP 直连白名单开关（关=通话由 UDP 策略决定去向：
     *  拒绝=不通话；CNS 隧道=免流但多一跳延迟）。NTP 对时不受本开关影响。 */
    val qqWechatUdpAllow: Boolean = true,
)
