// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.domain

/**
 * 通道族节点库与伪装素材常量（大杂烩订阅锚点快照）。
 * 令牌轮换走网关订阅（订阅里 T5 令牌动态生成），内置库只是首拉失败时的兜底（I8）。
 */
internal const val FfPrimaryToken = "683556433"
internal const val FfT5DisguiseHost = "153.3.236.22:443"

internal const val FfBaiduUserAgent =
    "okhttp/4.12.0 Dalvik/2.1.0 (Linux; U; Android 15; OnePlus PJZ110 Build/SP1A.241225.007) " +
        "baiduboxapp/14.50.2.15 (Baidu; P1 15)"

// 真机实测备用令牌 1967948331 无效，不预置（旧版注记）
internal fun t5Handshake(token: String = FfPrimaryToken): HandshakeSpec = HandshakeSpec(
    headers = listOf(
        HeaderSpec("Host", FfT5DisguiseHost),
        HeaderSpec("X-T5-Auth", token),
        HeaderSpec("User-Agent", FfBaiduUserAgent),
    ),
)

internal fun bareHandshake(pathSuffix: String? = null): HandshakeSpec = HandshakeSpec(
    delHost = true,
    pathSuffix = pathSuffix,
)

// 彩信直连中转：运营商 WAP 网关
internal const val FfWapRemarks = "WAP网关"
internal const val FfWapServer = "10.0.0.200"
internal const val FfWapPort = 80

// 网关订阅默认地址（聚合订阅：免流节点+动态令牌轮换源）
const val FreeFlowDefaultSubscriptionUrl = "" // 个人网关订阅地址(开源仓库不含), 使用内置节点或在免流页填入自己的订阅

// 订阅拉取 UA（工作区实测 clashmeta 才能拿到正确响应头/流量余量头；仅作远端 UA，不作身份标记）
const val FreeFlowSubscriptionUserAgent = "clashmeta/1.18.0"

/** 百度直连 14 节点（真机验证过的网关库，T5 三头）。 */
internal val FfBaiduNodes: List<NodeSpec> = listOf(
    NodeSpec("[北京]电信", "220.181.33.174", 443, t5Handshake()),
    NodeSpec("[北京]电信-2", "220.181.7.1", 443, t5Handshake()),
    NodeSpec("[北京]电信-3", "220.181.111.189", 443, t5Handshake()),
    NodeSpec("[南京]电信", "180.101.50.249", 443, t5Handshake()),
    NodeSpec("[南京]电信-2", "180.101.50.208", 443, t5Handshake()),
    NodeSpec("[广州]电信", "14.215.182.75", 443, t5Handshake()),
    NodeSpec("[苏州]联通", "157.0.146.158", 443, t5Handshake()),
    NodeSpec("[保定]联通", "110.242.70.68", 443, t5Handshake()),
    NodeSpec("[保定]联通-2", "110.242.70.69", 443, t5Handshake()),
    NodeSpec("[广州]联通", "163.177.17.6", 443, t5Handshake()),
    NodeSpec("[广州]联通-2", "163.177.17.189", 443, t5Handshake()),
    NodeSpec("[南京]联通", "153.3.237.117", 443, t5Handshake()),
    NodeSpec("[广州]移动", "183.240.98.84", 443, t5Handshake()),
    NodeSpec("[南京]移动", "36.155.169.188", 443, t5Handshake()),
)

/** 裸 CONNECT 族内置节点：原本的旧命名与 @path 特殊节点已全部删除，仅保留按要求添加的
 *  「百度直连-{城市}{运营商}」直连节点（del_host 裸握手）。 */
internal val FfTpboxNodes: List<NodeSpec> = listOf(
    NodeSpec("百度直连-南京联通", "153.3.237.117", 443, bareHandshake()),
    NodeSpec("百度直连-保定联通", "110.242.70.68", 443, bareHandshake()),
    NodeSpec("百度直连-广州联通", "163.177.17.6", 443, bareHandshake()),
    NodeSpec("百度直连-南京电信", "180.101.50.249", 443, bareHandshake()),
    NodeSpec("百度直连-广州电信", "14.215.182.75", 443, bareHandshake()),
    NodeSpec("百度直连-北京电信", "220.181.33.174", 443, bareHandshake()),
    NodeSpec("百度直连-苏州联通", "157.0.146.158", 443, bareHandshake()),
    NodeSpec("百度直连-广州移动", "183.240.98.84", 443, bareHandshake()),
    NodeSpec("百度直连-南京移动", "36.155.169.188", 443, bareHandshake()),
)

/** 彩信直连：T5 头 + WAP 中转（detour 由编译器注入），节点取自大杂烩订阅电信彩信直连组。 */
internal val FfMmsNodes: List<NodeSpec> = listOf(
    NodeSpec("[南京]彩信直连", "180.101.50.249", 443, t5Handshake()),
    NodeSpec("[南京]彩信直连-备", "180.101.50.208", 443, t5Handshake()),
    NodeSpec("[广州]彩信直连", "14.215.182.75", 443, t5Handshake()),
)

/** 族内置节点库（编译器随族整组重建；Del Host 仅百度族语义 → 换裸 CONNECT 库）。 */
fun freeFlowBuiltinNodes(familyKey: String): List<NodeSpec> = when {
    familyKey == TpboxBareFamily.key -> FfTpboxNodes
    familyKey == MmsFamily.key -> FfMmsNodes
    else -> FfBaiduNodes
}

/** 按 server:port 在内置库里认领节点（迁移器用：命中 = Builtin 货）。 */
fun builtinNodeAt(server: String, serverPort: Int): NodeSpec? =
    (FfBaiduNodes + FfTpboxNodes + FfMmsNodes)
        .firstOrNull { node -> node.server == server && node.serverPort == serverPort }
