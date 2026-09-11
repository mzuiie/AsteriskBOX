// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.domain

/**
 * 免流通道族：免流逻辑的一等公民。每族声明网关节点库、握手形态、已知封锁域；
 * 新通道（如阿里族）只需加一个实现 + 节点库即可接入。
 * 拨号语义在内核（path-in-request-line / del_host / 无默认 UA / 目标 IP 化），
 * 这里只做配置层拼装与校验。族身份存档用 key（稳定标识，不得改动）。
 */
sealed interface FreeFlowFamily {
    val key: String
    val title: String
    val cardHint: String

    /** 网关上游 DPI 已知封锁域（客户端无解，UI 提示 bypass 直连，见 I7）。 */
    val blockedSuffixes: List<String> get() = FreeFlowGatewayBlockedSuffixes
}

/** 百度直连（T5）：模拟百度系 App 的 CONNECT 请求走百度免流网关，百度系卡专用。 */
data object BaiduT5Family : FreeFlowFamily {
    override val key = "baidu_t5"
    override val title = "百度直连"
    override val cardHint = "模拟百度 App 的 CONNECT 请求（三件伪装头）走百度免流网关；百度系免流卡专用，日常主通道"
}

/** TPBox 裸握手：CONNECT 不带任何头（delHost）；节点库与百度直连同款网关。 */
data object TpboxBareFamily : FreeFlowFamily {
    override val key = "tpbox_bare"
    override val title = "裸 CONNECT"
    override val cardHint = "CONNECT 请求不带任何头，网关与百度直连同款，只是握手方式不同"
}

/** 彩信直连：T5 头 + 运营商 WAP 网关（10.0.0.200:80）中转（detour，clash 语义 dialer-proxy）。 */
data object MmsFamily : FreeFlowFamily {
    override val key = "mms"
    override val title = "彩信直连"
    override val cardHint = "仅用于彩信收发场景：请求经运营商 WAP 网关中转。日常上网请切回「百度直连」，用它跑普通流量会全量计费"
}

/** 族注册表（顺序即 UI 展示顺序）。 */
val FreeFlowFamilies: List<FreeFlowFamily> = listOf(BaiduT5Family, TpboxBareFamily, MmsFamily)

fun freeFlowFamilyByKey(key: String): FreeFlowFamily =
    FreeFlowFamilies.firstOrNull { family -> family.key == key } ?: BaiduT5Family

/** 网关上游 DPI 已知封锁域（mtl28 实测 503：google 全系；heytap/OPPO 族已进 bypass 直连清单）。 */
val FreeFlowGatewayBlockedSuffixes = listOf(
    "google.com",
    "googleapis.com",
    "googlevideo.com",
    "ytimg.com",
    "ggpht.com",
    "youtube.com",
)
