// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.OutboundGroupUpdateStatus
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleState
import app.managedOutboundGroupSelectorTag
import engine.singbox.config.parseSingBoxJson
import kotlinx.serialization.json.jsonPrimitive
import features.freeflow.compile.AirportSnapshot
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.compile.patchOutboundChainDetourJson
import features.freeflow.compile.freeFlowApplied
import features.freeflow.compile.withoutAirportMemberDetours
import features.freeflow.compile.withFreeFlowArtifactsApplied
import features.freeflow.compile.withoutFreeFlowArtifacts
import features.freeflow.compile.FreeFlowCompiler.InvariantId
import features.freeflow.domain.AirportSpec
import features.freeflow.domain.ChannelSpec
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.HeaderSpec
import features.freeflow.domain.HandshakeSpec
import features.freeflow.domain.illegalReason
import features.freeflow.domain.NodeSpec
import features.freeflow.domain.NodeSource
import features.freeflow.domain.SplitMode
import features.freeflow.domain.SplitSpec
import features.freeflow.domain.UdpPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 免流编译器回归：不变量逐条 + 区间管理 + 真值表门，JVM 单测不依赖 Android 运行时。 */
class FreeFlowCompilerTest {

    private fun compile(profile: FreeFlowProfile, base: AppState = AppState()) =
        FreeFlowCompiler.compile(base, profile)

    private fun defaultProfile(
        enabled: Boolean = true,
        transform: (FreeFlowProfile) -> FreeFlowProfile = { it },
    ): FreeFlowProfile = transform(FreeFlowProfile(enabled = enabled))

    @Test
    fun defaultProfileCompilesAndInvariantsHold() {
        val result = compile(defaultProfile())
        val artifacts = (result as FreeFlowCompiler.Result.Success).artifacts

        // I5① QUIC 双端口
        assertEquals(setOf("80", "443"), artifacts.routeRules.first { it.remarks == "ff_block_quic" }.port.toSet())
        // I5③ IPv6 三层收口
        assertEquals(6, artifacts.routeRules.first { it.remarks == "ff_block_ipv6" }.ipVersion)
        assertTrue(artifacts.dnsRules.any { it.remarks == "ff_aaaa_empty" })
        assertTrue(features.freeflow.compile.FreeFlowCompiler.Flag.ForceDisableIpv6 in artifacts.flags)
        // I5⑦ 私网直连用显式 ip_cidr
        assertFalse(artifacts.routeRules.any { it.ipIsPrivate })
        assertTrue(artifacts.routeRules.first { it.remarks == "ff_private_ip" }.ipCidr.contains("fd00::/8"))
        // I5④ UDP 防跳总闸
        assertNotNull(artifacts.routeRules.firstOrNull { it.remarks == "ff_udp_reject" })
        // I4 FakeIP 服务器在产物里
        assertTrue(artifacts.dnsServers.any { it.type == "fakeip" })
        // I5⑧ NTP 在 FakeIP 入口之前
        val ntpIndex = artifacts.dnsRules.indexOfFirst { it.remarks == "ff_ntp" }
        val entryIndex = artifacts.dnsRules.indexOfFirst { it.remarks == "ff_fakeip_entry" }
        assertTrue(ntpIndex in 0 until entryIndex)
        // 免流组身份走 ownerKey
        assertEquals(FreeFlowCompiler.FreeFlowOwnerKey, artifacts.groups.first().ownerKey)
        // T5 内置库成员真值表合法
        artifacts.outbounds
            .filter { it.groupId == artifacts.groups.first().id && it.type == "http" }
            .forEach { member ->
                assertNull(parseSingBoxJson(member.json).let { json ->
                    HandshakeSpec(
                        headers = (json["headers"] as? kotlinx.serialization.json.JsonObject)?.entries
                            ?.map { (name, value) -> HeaderSpec(name, value.jsonPrimitive.content) }.orEmpty(),
                    ).illegalReason()
                })
            }
    }

    @Test
    fun proxySideDnsRulesStrippedSoForeignDomainsHitFakeIp() {
        // 真机实证：geosite_google → DoT 8.8.8.8:853 骑免流网关 = YouTube 系域名解析死（网关白名单拒）
        val directTag = app.managedDnsServerTag(1, "国内DNS")
        val proxyTag = app.managedDnsServerTag(2, "国外DNS")
        val base = AppState(
            dnsServers = listOf(
                SingBoxDnsServerState(id = 1, remarks = "国内DNS", type = "https", server = "223.5.5.5"),
                SingBoxDnsServerState(id = 2, remarks = "国外DNS", type = "tls", server = "8.8.8.8", serverPort = "853"),
            ),
            dnsRules = listOf(
                SingBoxDnsRuleState(
                    id = 1,
                    remarks = "cn_direct",
                    matches = listOf(app.SingBoxDnsRuleMatchState(field = "domain_suffix", values = listOf("cn"))),
                    action = "route",
                    server = directTag,
                ),
                SingBoxDnsRuleState(
                    id = 2,
                    remarks = "google_proxy",
                    matches = listOf(app.SingBoxDnsRuleMatchState(field = "domain_suffix", values = listOf("google.com"))),
                    action = "route",
                    server = proxyTag,
                ),
            ),
        )
        val artifacts = (compile(defaultProfile(), base) as FreeFlowCompiler.Result.Success).artifacts

        // 免流 DNS 全接管：基础分流规则(国内外)整体忽略, 所有 A/AAAA 落 FakeIP——
        // 国内域名拿真实 IP 会让 UDP 连接失去域名映射, geosite 分流对国内 UDP 失效(真机实锤)
        assertFalse(artifacts.dnsRules.any { it.remarks == "google_proxy" })
        assertFalse(artifacts.dnsRules.any { it.remarks == "cn_direct" })
        // FakeIP 入口仍在
        assertTrue(artifacts.dnsRules.any { it.remarks == "ff_fakeip_entry" })
    }

    @Test
    fun chainedModeSplitsDomesticAndFinalsAirport() {
        val profile = defaultProfile { it.copy(
            airports = listOf(AirportSpec(name = "快跑", url = "https://sub.example.com/s")),
            split = SplitSpec(mode = SplitMode.Chained, activeAirport = "快跑"),
        ) }
        val artifacts = (compile(profile) as FreeFlowCompiler.Result.Success).artifacts

        // 国内分流规则在场，route.final 指向机场组 selector（不是通道组）
        assertTrue(artifacts.routeRules.any { it.remarks == "ff_domestic_split" })
        val airportGroup = artifacts.groups.first { it.name == "快跑" }
        assertEquals(
            managedOutboundGroupSelectorTag(airportGroup.id, airportGroup.name),
            artifacts.routeFinal,
        )
        // 链式 detour 注入纯函数：写入/幂等/非法 JSON 跳过
        val plainNode = """{"type":"vmess","server":"s","server_port":1}"""
        val chained = patchOutboundChainDetourJson(plainNode, "outbound_group_1_免流网关")
        assertEquals(
            """{"type":"vmess","server":"s","server_port":1,"detour":"outbound_group_1_免流网关"}""",
            chained,
        )
        // 同值幂等返回 null
        assertNull(
            patchOutboundChainDetourJson(
                """{"type":"vmess","detour":"outbound_group_1_免流网关"}""",
                "outbound_group_1_免流网关",
            ),
        )
        // 非法 JSON 原样跳过
        assertNull(patchOutboundChainDetourJson("not-json", "x"))
    }

    @Test
    fun airportMemberDanglingDetourStripped() {
        val state = AppState().copy(
            outboundGroups = listOf(
                OutboundGroupState(id = 56, name = "快跑·国内", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
                OutboundGroupState(id = 14, name = "免流网关", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
            ),
            outbounds = listOf(
                OutboundState(
                    id = 292,
                    groupId = 56,
                    remarks = "剩余流量",
                    type = "vmess",
                    json = """{"type":"vmess","detour":"outbound_group_14_免流网关"}""",
                ),
                OutboundState(
                    id = 293,
                    groupId = 14,
                    remarks = "通道节点",
                    type = "http",
                    json = """{"type":"http","detour":"x"}""",
                ),
            ),
        )
        val cleaned = state.withoutAirportMemberDetours()
        assertFalse(cleaned.outbounds.first().json.contains("detour"))
        // 通道组成员不动
        assertTrue(cleaned.outbounds.last().json.contains("detour"))
    }

    @Test
    fun airportOnlyUdpRidesSubscriptionCnNodes() {
        // 用户定版: 纯机场 UDP 统一走订阅国内节点组(·国内), 不分国内外、不走 CNS、与 UDP 策略无关
        val profile = defaultProfile { it.copy(
            airports = listOf(AirportSpec(name = "快跑", url = "https://sub.example.com/s")),
            split = SplitSpec(mode = SplitMode.AirportOnly, activeAirport = "快跑"),
        ) }
        val artifacts = (compile(profile) as FreeFlowCompiler.Result.Success).artifacts
        val cnRule = artifacts.routeRules.first { it.remarks == "ff_udp_airport_cn" }
        assertEquals(listOf("udp"), cnRule.network)
        val cnGroup = artifacts.groups.first { it.name == "快跑·国内" }
        assertEquals(
            managedOutboundGroupSelectorTag(cnGroup.id, cnGroup.name),
            cnRule.outbound,
        )
        // 机场 UDP 分流旧规则与 CNS 规则均不再生成(纯机场不建 CNS 组)
        assertTrue(artifacts.routeRules.none { it.remarks == "ff_udp_foreign_airport" })
        assertTrue(artifacts.routeRules.none { it.remarks == "ff_udp_domestic_airport" })
        assertTrue(artifacts.routeRules.none { it.remarks == "ff_udp_cns" })
    }

    @Test
    fun switchingAirportOnlyToSplitDropsDerivedGroups() {
        // 复刻 UseCase 套用语义: 摘快照→strip→恢复→编译→套用, 断言模式切换后派生组不残留
        val onlyProfile = defaultProfile { it.copy(
            airports = listOf(AirportSpec(name = "快跑", url = "https://sub.example.com/s")),
            split = SplitSpec(mode = SplitMode.AirportOnly, activeAirport = "快跑"),
        ) }
        val onlyState = AppState().applyProfile(onlyProfile)
        assertTrue(onlyState.outboundGroups.any { it.name == "快跑·国内" })

        val splitProfile = onlyProfile.copy(split = SplitSpec(mode = SplitMode.AirportDirect, activeAirport = "快跑"))
        val preservedIds = onlyState.outboundGroups
            .filter { it.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey && it.name == "快跑" }
            .mapTo(mutableSetOf()) { it.id }
        val preservedGroups = onlyState.outboundGroups.filter { it.id in preservedIds }
        val preservedOutbounds = onlyState.outbounds.filter { it.groupId in preservedIds }
        val stripped = onlyState.withoutFreeFlowArtifacts()
            .let { s -> s.copy(outboundGroups = s.outboundGroups + preservedGroups, outbounds = s.outbounds + preservedOutbounds) }
        val artifacts = (compile(splitProfile, stripped) as FreeFlowCompiler.Result.Success).artifacts
        val splitState = stripped.withFreeFlowArtifactsApplied(artifacts)

        assertFalse(splitState.outboundGroups.any { it.name == "快跑·国内" || it.name == "快跑·国外" })
        assertTrue(splitState.outboundGroups.any { it.name == "快跑" })
    }

    @Test
    fun splitModeUdpUnifiesViaCns() {
        // 用户定版: 分流模式 UDP 统一走 CNS(国外不再走机场节点), 白名单外全量兜底
        val cns = listOf(CnsSpec(name = "cns", server = "1.2.3.4", port = 443, password = "x"))
        val profile = defaultProfile { it.copy(
            udp = UdpPolicy.ViaCns,
            cnsNodes = cns,
            airports = listOf(AirportSpec(name = "快跑", url = "https://sub.example.com/s")),
            split = SplitSpec(mode = SplitMode.AirportDirect, activeAirport = "快跑"),
        ) }
        val artifacts = (compile(profile) as FreeFlowCompiler.Result.Success).artifacts

        assertTrue(artifacts.routeRules.none { it.remarks == "ff_udp_foreign_airport" })
        assertTrue(artifacts.routeRules.none { it.remarks == "ff_udp_domestic_airport" })
        val cnsRule = artifacts.routeRules.first { it.remarks == "ff_udp_cns" }
        assertEquals(listOf("udp"), cnsRule.network)
        // 纯免流模式同样统一走 CNS
        val freeOnly = (compile(defaultProfile { it.copy(udp = UdpPolicy.ViaCns, cnsNodes = cns) }) as FreeFlowCompiler.Result.Success).artifacts
        assertTrue(freeOnly.routeRules.any { it.remarks == "ff_udp_cns" })
    }

    @Test
    fun applyIsStableOnReapply() {
        val once = AppState().applyProfile(defaultProfile())
        val twice = once.let { state ->
            val stripped = state.withoutFreeFlowArtifacts()
            val artifacts = (compile(defaultProfile(), stripped) as FreeFlowCompiler.Result.Success).artifacts
            stripped.withFreeFlowArtifactsApplied(artifacts)
        }
        assertEquals(
            once.routeRules.map { it.remarks }.toSet(),
            twice.routeRules.map { it.remarks }.toSet(),
        )
        // 组 ID 递增属预期：routeFinal 各自指向本状态自己的免流组 selector
        assertEquals(once.routeFinal, managedOutboundGroupSelectorTag(once.outboundGroups.first().id, once.outboundGroups.first().name))
        assertEquals(twice.routeFinal, managedOutboundGroupSelectorTag(twice.outboundGroups.first().id, twice.outboundGroups.first().name))
    }

    @Test
    fun bareFamilyUsesBareNodes() {
        val artifacts = (compile(defaultProfile { profile ->
            profile.copy(channel = profile.channel.copy(familyKey = "tpbox_bare"))
        }) as FreeFlowCompiler.Result.Success).artifacts
        val members = artifacts.outbounds
            .filter { it.groupId == artifacts.groups.first().id && it.type == "http" }
        assertTrue(members.isNotEmpty())
        assertTrue(members.all { it.json.contains("\"del_host\":true") })
        assertTrue(members.none { it.json.contains("X-T5-Auth") })
    }

    @Test
    fun familySwitchKeepsManualNodesAndRebuildsBuiltin() {
        val manual = NodeSpec(
            remarks = "手添节点",
            server = "1.2.3.4",
            serverPort = 443,
            handshake = HandshakeSpec(delHost = true),
            source = NodeSource.Manual,
        )
        val staleBuiltin = NodeSpec(
            remarks = "[北京]电信",
            server = "220.181.33.174",
            serverPort = 443,
            handshake = features.freeflow.domain.t5Handshake(),
            source = NodeSource.Builtin,
        )
        val artifacts = (compile(
            defaultProfile { profile ->
                profile.copy(
                    channel = profile.channel.copy(familyKey = "tpbox_bare", nodes = listOf(manual, staleBuiltin)),
                )
            },
        ) as FreeFlowCompiler.Result.Success).artifacts
        val members = artifacts.outbounds
            .filter { it.groupId == artifacts.groups.first().id && it.type == "http" }
        // 新族内置库整组在 + 手添节点随族保留 + 旧族 Builtin 货不复活
        assertTrue(members.any { it.remarks == "手添节点" && it.json.contains("\"del_host\":true") })
        assertTrue(members.any { it.remarks == "百度直连-南京联通" })
        assertTrue(members.none { it.remarks == "[北京]电信" })
    }

    @Test
    fun halfBakedHandshakeFailsCompilation() {
        // Auth 无 Host（403 实锤形态）必须被真值表门拦下
        val broken = NodeSpec(
            remarks = "坏节点",
            server = "1.2.3.4",
            serverPort = 443,
            handshake = HandshakeSpec(
                headers = listOf(HeaderSpec("X-T5-Auth", "123")),
            ),
            source = NodeSource.Manual,
        )
        val result = compile(
            defaultProfile { profile ->
                profile.copy(channel = profile.channel.copy(nodes = listOf(broken)))
            },
        )
        assertTrue(result is FreeFlowCompiler.Result.Failed)
        assertEquals(InvariantId.ChannelHandshake, (result as FreeFlowCompiler.Result.Failed).invariant)
    }

    @Test
    fun cnsOutboundsLiveInOwnGroupAndRideChannelWhenPrepended() {
        val artifacts = (compile(
            defaultProfile { profile ->
                profile.copy(
                    udp = UdpPolicy.ViaCns,
                    cnsNodes = listOf(
                        CnsSpec(name = "", server = "10.0.0.1", port = 80, password = "pw", prependFree = true),
                    ),
                )
            },
        ) as FreeFlowCompiler.Result.Success).artifacts
        val channelGroup = artifacts.groups.first { it.name == FreeFlowCompiler.FreeFlowChannelGroupName }
        val cnsGroup = artifacts.groups.first { it.name == FreeFlowCompiler.FreeFlowCnsGroupName }
        val cnsMembers = artifacts.outbounds.filter { it.groupId == cnsGroup.id }
        assertTrue(cnsMembers.isNotEmpty())
        assertTrue(cnsMembers.none { it.groupId == channelGroup.id })
        assertTrue(cnsMembers.all { it.json.contains("\"detour\"") })
        // I11：CNS 配置不携带 udp_flag 串
        assertTrue(cnsMembers.none { it.json.contains("httpUDP") })
        val cnsRule = artifacts.routeRules.first { it.remarks == "ff_udp_cns" }
        assertEquals(
            app.managedOutboundGroupSelectorTag(cnsGroup.id, cnsGroup.name),
            cnsRule.outbound,
        )
    }

    @Test
    fun udpPoliciesEmitDistinctRuleSets() {
        val strict = (compile(defaultProfile()) as FreeFlowCompiler.Result.Success).artifacts
        assertFalse(strict.routeRules.any { it.remarks == "ff_udp_allow" || it.remarks == "ff_udp_cns" })

        val allowAll = (compile(defaultProfile { it.copy(udp = UdpPolicy.AllowAll) }) as FreeFlowCompiler.Result.Success).artifacts
        val rules = allowAll.routeRules
        assertTrue(rules.indexOfFirst { it.remarks == "ff_udp_allow" } < rules.indexOfFirst { it.remarks == "ff_udp_reject" })

        val viaCns = (
            compile(
                defaultProfile { profile ->
                    profile.copy(
                        udp = UdpPolicy.ViaCns,
                        cnsNodes = listOf(CnsSpec(name = "", server = "10.0.0.1", port = 80, password = "pw")),
                    )
                },
            ) as FreeFlowCompiler.Result.Success
            ).artifacts
        assertTrue(viaCns.routeRules.any { it.remarks == "ff_udp_cns" })
    }

    @Test
    fun airportDirectRequiresAirportGroup() {
        val missing = compile(
            defaultProfile { it.copy(split = SplitSpec(mode = SplitMode.AirportDirect)) },
        )
        assertTrue(missing is FreeFlowCompiler.Result.Failed)
        assertEquals(InvariantId.SplitTarget, (missing as FreeFlowCompiler.Result.Failed).invariant)

        val withAirportState = AppState().copy(
            outboundGroups = listOf(
                OutboundGroupState(id = 50, name = "我的机场", userAgent = "clashmeta/1.18.0", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
            ),
            nextOutboundGroupId = 51,
        )
        val ok = compile(
            defaultProfile { profile ->
                profile.copy(
                    split = SplitSpec(mode = SplitMode.AirportDirect, activeAirport = "我的机场"),
                    airports = listOf(features.freeflow.domain.AirportSpec(name = "我的机场")),
                )
            },
            base = withAirportState,
        )
        val artifacts = (ok as FreeFlowCompiler.Result.Success).artifacts
        assertEquals(
            app.managedOutboundGroupSelectorTag(50, "我的机场"),
            artifacts.routeFinal,
        )
        assertTrue(artifacts.routeRules.any { it.remarks == "ff_domestic_split" })
    }

    @Test
    fun airportOnlySplitsDomesticAndForeignToDerivedGroups() {
        val withAirportState = AppState().copy(
            outboundGroups = listOf(
                OutboundGroupState(id = 50, name = "我的机场", userAgent = "clashmeta/1.18.0", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
            ),
            outbounds = listOf(
                OutboundState(id = 500, groupId = 50, remarks = "香港-1", type = "vmess", json = "{\"type\":\"vmess\"}"),
            ),
            nextOutboundGroupId = 51,
            nextOutboundId = 501,
        )
        val artifacts = (compile(
            defaultProfile { profile ->
                profile.copy(
                    split = SplitSpec(mode = SplitMode.AirportOnly, activeAirport = "我的机场"),
                    airports = listOf(features.freeflow.domain.AirportSpec(name = "我的机场")),
                )
            },
            base = withAirportState,
        ) as FreeFlowCompiler.Result.Success).artifacts

        // 派生「·国内/·国外」两组各复制一份成员（源机场组在 base 里透传，不在产物 groups 中）
        val cnGroup = artifacts.groups.first { it.name == "我的机场·国内" }
        val finalGroup = artifacts.groups.first { it.name == "我的机场·国外" }
        assertEquals(1, artifacts.outbounds.count { it.groupId == cnGroup.id })
        assertEquals(1, artifacts.outbounds.count { it.groupId == finalGroup.id })
        assertEquals(
            managedOutboundGroupSelectorTag(cnGroup.id, cnGroup.name),
            artifacts.routeRules.first { it.remarks == "ff_domestic_split" }.outbound,
        )
        assertEquals(managedOutboundGroupSelectorTag(finalGroup.id, finalGroup.name), artifacts.routeFinal)

        // 缺机场组时编译失败（SplitTarget）
        val missing = compile(defaultProfile { it.copy(split = SplitSpec(mode = SplitMode.AirportOnly)) })
        assertTrue(missing is FreeFlowCompiler.Result.Failed)
        assertEquals(InvariantId.SplitTarget, (missing as FreeFlowCompiler.Result.Failed).invariant)
    }

    @Test
    fun stripRemovesOwnedAndLegacyArtifacts() {
        // owned 产物
        val applied = AppState().applyProfile(defaultProfile())
        assertTrue(FreeFlowCompiler.run { applied.freeFlowApplied() })
        val stripped = applied.withoutFreeFlowArtifacts()
        assertFalse(FreeFlowCompiler.run { stripped.freeFlowApplied() })
        assertTrue(stripped.dnsServers.none { it.type == "fakeip" })
        assertTrue(stripped.dnsRules.none { it.remarks.startsWith("ff_") })

        // 旧版 features/mtl 遗留：冻结组名 + mtl_ 规则 + UA 机场组
        val legacyGroupId = 77
        val airportGroupId = 78
        val legacy = AppState().copy(
            outboundGroups = listOf(
                OutboundGroupState(id = legacyGroupId, name = FreeFlowCompiler.LegacyChannelGroupName),
                OutboundGroupState(id = airportGroupId, name = "老机场", userAgent = "clashmeta/1.18.0"),
            ),
            outbounds = listOf(
                OutboundState(id = 1, groupId = legacyGroupId, remarks = "[测试]", type = "http", json = "{\"type\":\"http\",\"server\":\"1.2.3.4\",\"server_port\":443}"),
            ),
            routeRules = listOf(
                SingBoxRouteRuleState(id = 900, remarks = "mtl_telemetry", action = "reject"),
            ),
            routeFinal = app.managedOutboundGroupSelectorTag(legacyGroupId, FreeFlowCompiler.LegacyChannelGroupName),
            nextRouteRuleId = 901,
        )
        val legacyStripped = legacy.withoutFreeFlowArtifacts()
        assertFalse(FreeFlowCompiler.run { legacyStripped.freeFlowApplied() })
        assertTrue(legacyStripped.outboundGroups.isEmpty())
        assertTrue(legacyStripped.routeRules.isEmpty())
        assertEquals("", legacyStripped.routeFinal)
    }

    @Test
    fun channelGroupIsPlacedFirstForProxyPage() {
        val base = AppState().copy(
            outboundGroups = listOf(OutboundGroupState(id = 9, name = "普通组")),
            nextOutboundGroupId = 10,
        )
        val applied = base.withFreeFlowArtifactsApplied(
            (compile(defaultProfile(), base) as FreeFlowCompiler.Result.Success).artifacts,
        )
        assertEquals(FreeFlowCompiler.FreeFlowChannelGroupName, applied.outboundGroups.first().name)
    }

    /** 测试辅助：编译并落位（等价 UseCase 的 apply 路径，不依赖 Android 运行时）。 */
    private fun AppState.applyProfile(profile: FreeFlowProfile): AppState {
        val compiled = compile(profile, this) as FreeFlowCompiler.Result.Success
        return withoutFreeFlowArtifacts().withFreeFlowArtifactsApplied(compiled.artifacts)
    }
}

