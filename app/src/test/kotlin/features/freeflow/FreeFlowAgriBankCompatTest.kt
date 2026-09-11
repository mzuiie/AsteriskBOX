// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import app.AppState
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.domain.AirportSpec
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.UdpPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 农行兼容实验开关（默认关）：农行 TCP 走 CNS 中继（网关白名单拒非常规端口对的软依赖兜底）。 */
class FreeFlowAgriBankCompatTest {

    private fun compile(profile: FreeFlowProfile, base: AppState = AppState()) =
        FreeFlowCompiler.compile(base, profile)

    private fun compatProfile(withCns: Boolean): FreeFlowProfile {
        val base = FreeFlowProfile(enabled = true).let { p ->
            p.copy(
                udp = UdpPolicy.Strict,
                guards = p.guards.copy(agriBankCompatEnabled = true),
            )
        }
        return if (withCns) {
            base.copy(cnsNodes = listOf(CnsSpec(server = "39.98.71.150", port = 50000, password = "x")))
        } else {
            base
        }
    }

    @Test
    fun offByDefaultInjectsNothing() {
        val artifacts = (compile(FreeFlowProfile(enabled = true)) as FreeFlowCompiler.Result.Success).artifacts
        assertFalse(artifacts.routeRules.any { it.remarks == "ff_agribank_cns" })
    }

    @Test
    fun onWithoutCnsCompilesWithoutRule() {
        // 软依赖：没配 CNS 不报错、不生成规则，开关不起作用
        val result = compile(compatProfile(withCns = false))
        val artifacts = (result as FreeFlowCompiler.Result.Success).artifacts
        assertFalse(artifacts.routeRules.any { it.remarks == "ff_agribank_cns" })
    }

    @Test
    fun airportIsNotARelayLeg() {
        // 机场腿经用户否决：只配机场（无 CNS）不得生成中继规则
        val profile = compatProfile(withCns = false).copy(
            airports = listOf(AirportSpec(name = "miaona", url = "https://example.com/sub")),
        )
        val artifacts = (compile(profile) as FreeFlowCompiler.Result.Success).artifacts
        assertFalse(artifacts.routeRules.any { it.remarks == "ff_agribank_cns" })
    }

    @Test
    fun onWithCnsRoutesBankTcpToCnsGroup() {
        val artifacts = (compile(compatProfile(withCns = true)) as FreeFlowCompiler.Result.Success).artifacts
        val rules = artifacts.routeRules
        val relay = rules.first { it.remarks == "ff_agribank_cns" }
        assertEquals(listOf("tcp"), relay.network)
        assertEquals(listOf("com.android.bankabc"), relay.packageName)
        val cnsGroup = artifacts.groups.first { it.name == FreeFlowCompiler.FreeFlowCnsGroupName }
        assertEquals(
            app.managedOutboundGroupSelectorTag(cnsGroup.id, cnsGroup.name),
            relay.outbound,
        )
        // 排在遥测拦截之前防误拦；QUIC/IPv6 防跳拒绝仍在其前生效（不豁免）
        val quic = rules.indexOfFirst { it.remarks == "ff_block_quic" }
        val telemetry = rules.indexOfFirst { it.remarks == "ff_telemetry" }
        val relayIndex = rules.indexOf(relay)
        assertTrue(quic < relayIndex && relayIndex < telemetry)
    }
}
