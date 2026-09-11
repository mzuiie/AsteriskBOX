// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.SingBoxRouteRuleState
import app.managedOutboundGroupSelectorTag
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.compile.withFreeFlowArtifactsApplied
import features.freeflow.compile.withoutFreeFlowArtifacts
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.SplitMode
import features.freeflow.domain.UdpPolicy
import features.freeflow.migrate.FreeFlowMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧版（features/mtl）状态 → 声明档一次性迁移回归：识别、推导、编译落位。 */
class FreeFlowMigrationTest {

    private fun legacyState(): AppState {
        val legacyGroupId = 41
        val airportGroupId = 42
        val cnsOutboundId = 7
        val memberJson =
            "{\"type\":\"http\",\"server\":\"220.181.33.174\",\"server_port\":443," +
                "\"headers\":{\"Host\":\"153.3.236.22:443\",\"X-T5-Auth\":\"683556433\"," +
                "\"User-Agent\":\"okhttp/4.12.0 baiduboxapp/14.50.2.15\"}}"
        val cnsJson =
            "{\"type\":\"cns\",\"server\":\"10.0.0.1\",\"server_port\":80,\"password\":\"pw\",\"masking\":true," +
                "\"detour\":\"outbound_group_${legacyGroupId}_免流网关节点\"}"
        val airportTag = managedOutboundGroupSelectorTag(airportGroupId, "老机场")
        return AppState().copy(
            outboundGroups = listOf(
                OutboundGroupState(
                    id = legacyGroupId,
                    name = "免流网关节点",
                    url = "https://wangka.example/wangka1",
                    userAgent = "clashmeta/1.18.0",
                    updateViaProxy = true,
                ),
                OutboundGroupState(
                    id = airportGroupId,
                    name = "老机场",
                    url = "https://airport.example/sub",
                    userAgent = "clashmeta/1.18.0",
                    updateViaProxy = true,
                ),
            ),
            outbounds = listOf(
                OutboundState(id = 1, groupId = legacyGroupId, remarks = "[北京]电信", type = "http", json = memberJson),
                OutboundState(id = cnsOutboundId, groupId = legacyGroupId, remarks = "CNS-UDP隧道", type = "cns", json = cnsJson),
            ),
            routeRules = listOf(
                SingBoxRouteRuleState(id = 501, remarks = "mtl_private_ip", ipIsPrivate = true, action = "route", outbound = "outbound_direct"),
                SingBoxRouteRuleState(id = 502, remarks = "mtl_udp_cns", network = listOf("udp"), action = "route", outbound = "outbound_group_${legacyGroupId}_CNS隧道"),
                SingBoxRouteRuleState(id = 503, remarks = "mtl_udp_reject", network = listOf("udp"), action = "reject"),
                SingBoxRouteRuleState(id = 504, remarks = "mtl_domestic_split", action = "route", outbound = "outbound_group_${legacyGroupId}_免流网关节点"),
                SingBoxRouteRuleState(id = 505, remarks = "mtl_telemetry", action = "reject"),
            ),
            nextRouteRuleId = 506,
            routeFinal = airportTag,
        )
    }

    @Test
    fun legacyArtifactsAreDetected() {
        assertTrue(FreeFlowMigrator.legacyArtifactsDetected(legacyState()))
        assertFalse(FreeFlowMigrator.legacyArtifactsDetected(AppState()))
    }

    @Test
    fun deriveProfileFromLegacyState() {
        val profile = FreeFlowMigrator.deriveProfile(legacyState())
        assertNotNull(profile)
        profile!!
        assertTrue(profile.enabled)
        // 族识别：T5 三头 → baidu_t5；订阅 URL 继承
        assertEquals("baidu_t5", profile.channel.familyKey)
        assertEquals("https://wangka.example/wangka1", profile.channel.subscriptionUrl)
        assertTrue(profile.channel.nodes.isNotEmpty())
        // CNS 迁移 + prependFree（旧 detour 指向通道组）
        assertEquals(1, profile.cnsNodes.size)
        assertTrue(profile.cnsNodes.first().prependFree)
        assertEquals("10.0.0.1", profile.cnsNodes.first().server)
        // UDP 策略：mtl_udp_cns → ViaCns
        assertEquals(UdpPolicy.ViaCns, profile.udp)
        // 分流：mtl_domestic_split → AirportDirect + 活跃机场组名
        assertEquals(SplitMode.AirportDirect, profile.split.mode)
        assertEquals("老机场", profile.split.activeAirport)
        assertTrue(profile.airports.any { it.name == "老机场" })
        // 遥测开关继承
        assertTrue(profile.guards.telemetryBlockEnabled)
    }

    @Test
    fun migratedProfileCompilesIntoOwnedArtifacts() {
        val profile = FreeFlowMigrator.deriveProfile(legacyState())!!
        val base = legacyState().withoutFreeFlowArtifacts()
        val compiled = FreeFlowCompiler.compile(base, profile)
        val artifacts = (compiled as FreeFlowCompiler.Result.Success).artifacts
        val applied = base.withFreeFlowArtifactsApplied(artifacts)

        // 新身份结构化：组带 ownerKey，规则全量换 ff_ 前缀，旧标记清零
        assertTrue(applied.outboundGroups.all { group -> group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey })
        assertFalse(applied.routeRules.any { it.remarks.startsWith("mtl_") })
        assertTrue(applied.routeRules.any { it.remarks == "ff_udp_cns" })
        assertTrue(applied.routeRules.any { it.remarks == "ff_domestic_split" })
        assertEquals(FreeFlowCompiler.FreeFlowChannelGroupName, applied.outboundGroups.first().name)
        // 编译期 AirportDirect 前置校验：档里没有机场名时会失败（本例已带机场）
        assertTrue(applied.routeFinal.isNotBlank())
    }

    @Test
    fun migrationWithoutChannelGroupFallsBackToDisabledProfile() {
        assertNull(FreeFlowMigrator.deriveProfile(AppState()))
    }

    private fun assertNull(value: FreeFlowProfile?) {
        org.junit.Assert.assertNull(value)
    }
}
