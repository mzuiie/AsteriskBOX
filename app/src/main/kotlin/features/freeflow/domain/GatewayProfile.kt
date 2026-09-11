// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.domain

import kotlinx.serialization.Serializable

/** 探测维度（5 组，覆盖跳点瓶颈 P3 的策略上限决定变量）。 */
@Serializable
enum class GatewayProbeKey {
    /** 百度白名单域（www.baidu.com:443）。 */
    BaiduDomain,

    /** 任意外部域（www.qq.com:443）。 */
    ExternalDomain,

    /** 裸 IP:port（223.5.5.5:443）——决定内核目标 IP 化是否可用（I3 保险丝）。 */
    RawIp,

    /** 非白名单国内域（www.jd.com:443）——决定国内分流走通道是否有效。 */
    DomesticDomain,

    /** 网关 80 端口（223.5.5.5:80 目标）——部分网关双端口监听。 */
    PlainHttp80,
}

@Serializable
data class GatewayProbeEntry(
    val target: String,
    val status: String,
)

/**
 * 网关能力档案（按网关 IP 缓存，7 天过期 + 手动重探）。
 * 探测结论只驱动"建议"，不自动改配置（守住审计 #5 结论）。
 */
@Serializable
data class GatewayProfile(
    val server: String,
    val atMillis: Long,
    val probes: Map<String, GatewayProbeEntry> = emptyMap(),
    val verdict: GatewayVerdict = GatewayVerdict.Unknown,
)

/** 网关放行策略结论（按域名维度推导；裸 IP/80 结果留在 probes 里供建议读取）。 */
@Serializable
enum class GatewayVerdict {
    /** 任意外部域名 CONNECT 放行，国内分流可整体走免流通道。 */
    AllPass,

    /** 仅白名单（百度系）放行，非白名单目标走通道会被网关拒绝。 */
    WhitelistOnly,

    /** 网关不可达或全拒，不调整分流。 */
    Unreachable,

    Unknown,
}

fun computeGatewayVerdict(statusOf: (GatewayProbeKey) -> String?): GatewayVerdict = when {
    statusOf(GatewayProbeKey.ExternalDomain) == "200" -> GatewayVerdict.AllPass
    statusOf(GatewayProbeKey.BaiduDomain) == "200" -> GatewayVerdict.WhitelistOnly
    statusOf(GatewayProbeKey.ExternalDomain) != null ||
        statusOf(GatewayProbeKey.BaiduDomain) != null -> GatewayVerdict.Unreachable
    else -> GatewayVerdict.Unknown
}

enum class SuggestionKind {
    /** 目标 IP 化被网关拒绝：通道切回域名形态（I3 保险丝）。 */
    SwitchToDomainForm,

    /** 网关不可达/全拒：检查令牌或换节点。 */
    CheckTokenOrNode,
}

data class FreeFlowSuggestion(
    val kind: SuggestionKind,
    val message: String,
)

/** 档案 → 建议集（纯函数；bypass 直连建议已随 ff_bypass_direct 删除）。 */
fun GatewayProfile.suggestions(): List<FreeFlowSuggestion> {
    val result = mutableListOf<FreeFlowSuggestion>()
    val statusOf: (GatewayProbeKey) -> String? = { key -> probes[key.toString()]?.status }
    val rawIp = statusOf(GatewayProbeKey.RawIp)
    when (verdict) {
        GatewayVerdict.Unreachable -> result += FreeFlowSuggestion(
            kind = SuggestionKind.CheckTokenOrNode,
            message = "网关不可达或全拒：先检查令牌是否过期（大量 503），再换节点重探",
        )
        else -> Unit
    }
    if ((verdict == GatewayVerdict.AllPass || verdict == GatewayVerdict.WhitelistOnly) &&
        rawIp != null && rawIp != "200"
    ) {
        result += FreeFlowSuggestion(
            kind = SuggestionKind.SwitchToDomainForm,
            message = "裸 IP 目标被网关拒绝：保持域名形态 CONNECT（勿启用目标 IP 化）",
        )
    }
    return result
}
