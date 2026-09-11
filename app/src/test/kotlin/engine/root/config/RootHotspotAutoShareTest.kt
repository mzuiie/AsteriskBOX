// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootHotspotAutoShareTest {

    @Test
    fun prefixInterfacesUnionStaticCandidatesWithManual() {
        assertEquals(
            listOf("wlan+", "ap+", "softap+", "swlan+", "rndis+", "usb+", "bnep+", "bt-pan+", "eth+", "wlan1", "wlan2", "wlan3"),
            RootHotspotAutoShare.prefixInterfaces(listOf("wlan+")),
        )
    }

    @Test
    fun exactInterfacesPreFillCandidatesAndKeepLive() {
        val live = listOf("lo", "wlan0", "wlan1", "ap0", "rmnet_data0", "asterisk0", "ccmni0", "dummy0")
        val result = RootHotspotAutoShare.exactInterfaces(emptyList(), live)
        // 预填候选全覆盖（后开热点无需重启）+ 实测接口并入 + station/上行口不混入
        assertTrue(result.containsAll(RootHotspotAutoShare.ExactCandidates))
        assertTrue(result.containsAll(listOf("wlan1", "ap0")))
        assertFalse(result.contains("wlan0"))
        assertFalse(result.contains("rmnet_data0"))
        assertFalse(result.contains("ccmni0"))
    }

    @Test
    fun manualExactEntriesSurviveUnion() {
        val result = RootHotspotAutoShare.exactInterfaces(listOf("ap9", "swlan0"), emptyList())
        assertTrue(result.containsAll(listOf("ap9", "swlan0")))
        assertTrue(result.containsAll(RootHotspotAutoShare.ExactCandidates))
    }

    @Test
    fun stationTunnelAndUpstreamNamesRejected() {
        assertFalse(RootHotspotAutoShare.isHotspotInterface("wlan0"))
        assertFalse(RootHotspotAutoShare.isHotspotInterface("lo"))
        assertFalse(RootHotspotAutoShare.isHotspotInterface("asterisk0"))
        assertFalse(RootHotspotAutoShare.isHotspotInterface("rmnet_data0"))
        assertTrue(RootHotspotAutoShare.isHotspotInterface("bt-pan0"))
        assertTrue(RootHotspotAutoShare.isHotspotInterface("wlan3"))
    }
}
