// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import app.AppState
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.domain.FreeFlowProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QQ/微信放行开关：关=不生成通话 UDP 直连白名单规则（NTP 不受影响）。 */
class FreeFlowUdpWhitelistTest {

    private fun compile(profile: FreeFlowProfile) =
        (FreeFlowCompiler.compile(AppState(), profile) as FreeFlowCompiler.Result.Success).artifacts

    @Test
    fun allowOnByDefaultKeepsWhitelistRules() {
        val rules = compile(FreeFlowProfile(enabled = true)).routeRules
        assertNotNull(rules.firstOrNull { it.remarks == "ff_udp_whitelist_apps" })
        assertNotNull(rules.firstOrNull { it.remarks == "ff_udp_whitelist_domains" })
    }

    @Test
    fun allowOffDropsWhitelistKeepsNtp() {
        val profile = FreeFlowProfile(enabled = true).let { p ->
            p.copy(guards = p.guards.copy(qqWechatUdpAllow = false))
        }
        val rules = compile(profile).routeRules
        assertFalse(rules.any { it.remarks == "ff_udp_whitelist_apps" })
        assertFalse(rules.any { it.remarks == "ff_udp_whitelist_domains" })
        assertNotNull(rules.firstOrNull { it.remarks == "ff_udp_ntp" })
    }
}
