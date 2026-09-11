// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import features.freeflow.domain.GatewayProbeEntry
import features.freeflow.domain.GatewayProbeKey
import features.freeflow.domain.GatewayProfile
import features.freeflow.domain.GatewayVerdict
import features.freeflow.domain.computeGatewayVerdict
import features.freeflow.domain.suggestions
import features.freeflow.domain.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网关能力档案 → 建议集（纯函数）回归。 */
class GatewaySuggestionsTest {

    private fun profile(
        probes: Map<GatewayProbeKey, String>,
        verdict: GatewayVerdict = computeGatewayVerdict { key -> probes[key] },
    ): GatewayProfile = GatewayProfile(
        server = "1.2.3.4:443",
        atMillis = 0L,
        probes = probes.map { (key, status) -> key.name to GatewayProbeEntry("t", status) }.toMap(),
        verdict = verdict,
    )

    @Test
    fun verdictDerivation() {
        assertEquals(
            GatewayVerdict.AllPass,
            computeGatewayVerdict { key ->
                when (key) {
                    GatewayProbeKey.ExternalDomain -> "200"
                    else -> "403"
                }
            },
        )
        assertEquals(
            GatewayVerdict.WhitelistOnly,
            computeGatewayVerdict { key ->
                when (key) {
                    GatewayProbeKey.BaiduDomain -> "200"
                    else -> "403"
                }
            },
        )
        assertEquals(
            GatewayVerdict.Unreachable,
            computeGatewayVerdict { _ -> "timeout" },
        )
        assertEquals(GatewayVerdict.Unknown, computeGatewayVerdict { _ -> null })
    }

    @Test
    fun unreachableSuggestsTokenCheck() {
        val result = profile(mapOf(GatewayProbeKey.BaiduDomain to "timeout")).suggestions()
        assertTrue(result.any { it.kind == SuggestionKind.CheckTokenOrNode })
    }

    @Test
    fun whitelistOnlyEmitsNoBypassAdvice() {
        val result = profile(mapOf(GatewayProbeKey.BaiduDomain to "200")).suggestions()
        assertTrue(result.none { it.message.contains("bypass") || it.message.contains("直连") })
    }

    @Test
    fun rawIpRejectSuggestsDomainForm() {
        val result = profile(
            mapOf(
                GatewayProbeKey.ExternalDomain to "200",
                GatewayProbeKey.BaiduDomain to "200",
                GatewayProbeKey.RawIp to "403",
            ),
        ).suggestions()
        assertTrue(result.any { it.kind == SuggestionKind.SwitchToDomainForm })
    }
}
