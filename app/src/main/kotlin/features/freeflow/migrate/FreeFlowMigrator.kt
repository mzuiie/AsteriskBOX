// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.migrate

import app.AppState
import app.OutboundGroupState
import app.managedOutboundGroupSelectorTag
import app.OutboundState
import engine.singbox.config.MtlCnsOutboundType
import engine.singbox.config.parseSingBoxJson
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.domain.AirportSpec
import features.freeflow.domain.ChannelSpec
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.FreeFlowSubscriptionUserAgent
import features.freeflow.domain.GuardSpec
import features.freeflow.domain.HeaderSpec
import features.freeflow.domain.HandshakeSpec
import features.freeflow.domain.NodeSource
import features.freeflow.domain.NodeSpec
import features.freeflow.domain.SplitMode
import features.freeflow.domain.SplitSpec
import features.freeflow.domain.UdpPolicy
import features.freeflow.domain.builtinNodeAt
import features.freeflow.domain.headerValue
import features.freeflow.domain.illegalReason
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 旧版（features/mtl 模板大改写时代）状态 → FreeFlowProfile 一次性迁移（纯函数，JVM 可测）。
 * 识别靠旧标记：冻结组名/UA 兼标记/规则前缀/JSON 字符串嗅探——只在这一处允许嗅探，
 * 迁移完成后身份全部结构化（ownerKey + meta）。
 */
object FreeFlowMigrator {

    /** 旧版痕迹是否在场（组名/规则前缀/UA 标记/CNS 类型/fakeip 服务器）。 */
    fun legacyArtifactsDetected(state: AppState): Boolean {
        if (state.outboundGroups.any { group -> group.name == FreeFlowCompiler.LegacyChannelGroupName }) return true
        if (state.outbounds.any { outbound -> outbound.type == MtlCnsOutboundType }) return true
        if (state.routeRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.LegacyRuleRemarksPrefix) }) return true
        if (state.dnsRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.LegacyRuleRemarksPrefix) }) return true
        if (state.dnsServers.any { server -> server.type == "fakeip" && server.remarks == "fakeip" }) return true
        return state.outboundGroups.any { group ->
            group.userAgent == FreeFlowSubscriptionUserAgent && group.ownerKey == null
        }
    }

    /** 从旧状态推导声明档；未识别到免流组时返回 null（调用方决定是否兜底默认档）。 */
    fun deriveProfile(state: AppState): FreeFlowProfile? {
        val channelGroup = state.outboundGroups.firstOrNull { group ->
            group.name == FreeFlowCompiler.LegacyChannelGroupName ||
                (group.name == FreeFlowCompiler.FreeFlowChannelGroupName && group.ownerKey == null)
        } ?: return null
        // 王卡动态族已移除：旧档里 Q-GUID 头成员不导入（它们对百度网关无意义），由内置库兜底
        val members = state.outbounds
            .filter { outbound -> outbound.groupId == channelGroup.id }
            .filter { outbound -> outbound.type == "http" }
            .filter { outbound -> !outbound.json.contains("\"Q-GUID\"") }

        // 族识别（旧版嗅探语义：彩信 detour → 裸 del_host → 默认百度 T5；Del Host 开关已并入裸族）
        val hasMmsRelay = members.any { outbound -> outbound.json.contains("\"detour\"") }
        val hasDelHost = members.any { outbound -> outbound.json.contains("\"del_host\"") }
        val familyKey = when {
            hasMmsRelay -> "mms"
            hasDelHost -> "tpbox_bare"
            else -> "baidu_t5"
        }

        // 成员 → NodeSpec（握手形态从 JSON 反解，货源按内置库认领）
        val nodes = members.mapNotNull { member ->
            parseNodeSpec(member)
        }.distinctBy { node -> "${node.server}:${node.serverPort}:${node.remarks}" }
        val subscriptionUrl = channelGroup.url.orEmpty()
        val udp = deriveUdpPolicy(state)
        val cnsNodes = state.outbounds
            .filter { outbound -> outbound.type == MtlCnsOutboundType }
            .mapNotNull { outbound -> parseCnsSpec(outbound) }
        val airports = state.outboundGroups
            .filter { group ->
                group.userAgent == FreeFlowSubscriptionUserAgent &&
                    group.ownerKey == null &&
                    group.name != FreeFlowCompiler.LegacyChannelGroupName &&
                    group.name != FreeFlowCompiler.FreeFlowChannelGroupName &&
                    group.name != FreeFlowCompiler.FreeFlowCnsGroupName
            }
            .map { group -> AirportSpec(name = group.name, url = group.url) }
        val split = deriveSplit(state)
        val guards = deriveGuards(state)
        return FreeFlowProfile(
            enabled = true,
            channel = ChannelSpec(
                familyKey = familyKey,
                nodes = nodes,
                subscriptionUrl = subscriptionUrl,
            ),
            udp = udp,
            cnsNodes = cnsNodes,
            airports = airports,
            split = split,
            guards = guards,
        )
    }

    private fun deriveUdpPolicy(state: AppState): UdpPolicy {
        val ruleRemarks = state.routeRules.map { rule -> rule.remarks }.toSet()
        return when {
            "mtl_udp_allow" in ruleRemarks -> UdpPolicy.AllowAll
            "mtl_udp_cns" in ruleRemarks -> UdpPolicy.ViaCns
            else -> UdpPolicy.Strict
        }
    }

    private fun parseCnsSpec(outbound: OutboundState): CnsSpec? = runCatching {
        val json = parseSingBoxJson(outbound.json)
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        CnsSpec(
            name = outbound.remarks.takeUnless { remarks ->
                remarks == FreeFlowCompiler.LegacyCnsRemarks ||
                    remarks == FreeFlowCompiler.FreeFlowCnsGroupName
            }.orEmpty(),
            server = str("server"),
            port = str("server_port").toIntOrNull() ?: 80,
            password = str("password"),
            masking = str("masking") == "true",
            maskHost = str("mask_host"),
            prependFree = json["detour"] != null,
            proxyKey = str("proxy_key").ifBlank { "Meng" },
            udpFlag = str("udp_flag").ifBlank { "httpUDP" },
        )
    }.getOrNull()

    private fun parseNodeSpec(outbound: OutboundState): NodeSpec? = runCatching {
        val json = parseSingBoxJson(outbound.json)
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val server = str("server").takeIf(String::isNotBlank) ?: return null
        val serverPort = str("server_port").toIntOrNull() ?: return null
        val headers = runCatching {
            (json["headers"] as? kotlinx.serialization.json.JsonObject)
                ?.entries?.map { (name, value) ->
                    HeaderSpec(name = name, value = value.jsonPrimitive.contentOrNull.orEmpty())
                }.orEmpty()
        }.getOrDefault(emptyList())
        val handshake = HandshakeSpec(
            delHost = str("del_host") == "true",
            headers = headers,
            pathSuffix = str("path").takeIf(String::isNotBlank),
        )
        NodeSpec(
            remarks = outbound.remarks,
            server = server,
            serverPort = serverPort,
            handshake = handshake,
            source = if (builtinNodeAt(server, serverPort) != null) NodeSource.Builtin else NodeSource.Manual,
        )
    }.getOrNull()?.takeIf { node -> node.handshake.illegalReason() == null }

    private fun deriveSplit(state: AppState): SplitSpec {
        val hasDomestic = state.routeRules.any { rule -> rule.remarks == "mtl_domestic_split" }
        if (!hasDomestic) return SplitSpec()
        val activeName = state.routeFinal.takeIf(String::isNotBlank)
            ?.let { tag -> state.outboundGroups.firstOrNull { group -> managedOutboundGroupSelectorTag(group.id, group.name) == tag }?.name }
        return SplitSpec(mode = SplitMode.AirportDirect, activeAirport = activeName)
    }

    private fun deriveGuards(state: AppState): GuardSpec {
        var guards = GuardSpec()
        state.routeRules.firstOrNull { rule -> rule.remarks == "mtl_udp_games" }
            ?.packageName?.let { packages ->
                guards = guards.copy(gameUdpPackages = packages)
            }
        val telemetry = state.routeRules.any { rule -> rule.remarks == "mtl_telemetry" }
        guards = guards.copy(telemetryBlockEnabled = telemetry)
        return guards
    }

    /** 保留机场组名单（迁移时这些组不被 strip 清掉，成员原样透传）。 */
    fun airportNamesToKeep(profile: FreeFlowProfile): Set<String> =
        profile.airports.map { airport -> airport.name }.toSet()
}
