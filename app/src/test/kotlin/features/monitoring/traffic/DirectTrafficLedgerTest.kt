// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.monitoring.traffic

import app.ManagedDirectOutboundTag
import engine.singbox.runtime.SingBoxConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 直连观测账本：live 累计、关闭入账、TCP/UDP 分桶、设计内直连分列与净额。 */
class DirectTrafficLedgerTest {

    private fun directConn(
        id: String,
        up: Long,
        down: Long,
        process: String = "",
        network: String = "tcp",
        destination: String = "1.2.3.4:443",
    ) = SingBoxConnection(
        id = id,
        outbound = ManagedDirectOutboundTag,
        uploadBytes = up,
        downloadBytes = down,
        process = process,
        network = network,
        destinationAddress = destination,
    )

    private fun proxiedConn(id: String, up: Long, down: Long) =
        SingBoxConnection(id = id, outbound = "outbound_group_4_免流网关", uploadBytes = up, downloadBytes = down)

    @Test
    fun accumulatesLiveGrowthPerConnection() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(directConn("a", 100, 200, "com.example.a")))
        assertEquals(300L, ledger.snapshot.value.totalBytes)
        ledger.onPoll(listOf(directConn("a", 150, 260, "com.example.a")))
        assertEquals(410L, ledger.snapshot.value.totalBytes)
        assertEquals(410L, ledger.snapshot.value.perAppBytes["com.example.a"])
    }

    @Test
    fun closedConnectionIsBankedOnceAndNotDoubleCounted() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(directConn("a", 100, 100, "com.example.a")))
        ledger.onPoll(emptyList())
        assertEquals(200L, ledger.snapshot.value.totalBytes)
        ledger.onPoll(listOf(directConn("b", 10, 20)))
        assertEquals(230L, ledger.snapshot.value.totalBytes)
    }

    @Test
    fun proxiedTrafficIsNotCounted() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(proxiedConn("a", 1_000_000, 1_000_000)))
        assertEquals(0L, ledger.snapshot.value.totalBytes)
    }

    @Test
    fun connectionThatLeavesDirectIsBankedAtLastDirectValue() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(directConn("a", 50, 50, "com.example.a")))
        ledger.onPoll(listOf(proxiedConn("a", 500, 500)))
        assertEquals(100L, ledger.snapshot.value.totalBytes)
    }

    @Test
    fun chainContainingDirectCounts() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(
            listOf(
                SingBoxConnection(
                    id = "a",
                    outbound = "outbound_group_4_免流网关",
                    chains = listOf("outbound_group_4_免流网关", ManagedDirectOutboundTag),
                    uploadBytes = 10,
                    downloadBytes = 20,
                ),
            ),
        )
        assertEquals(30L, ledger.snapshot.value.totalBytes)
    }

    @Test
    fun tcpAndUdpAreCountedSeparately() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(
            listOf(
                directConn("t", 100, 50, network = "tcp"),
                directConn("u", 30, 70, network = "udp"),
            ),
        )
        val snapshot = ledger.snapshot.value
        assertEquals(100L, snapshot.tcpUploadBytes)
        assertEquals(50L, snapshot.tcpDownloadBytes)
        assertEquals(30L, snapshot.udpUploadBytes)
        assertEquals(70L, snapshot.udpDownloadBytes)
        assertEquals(250L, snapshot.totalBytes)
    }

    @Test
    fun qqWechatUdpAndNtpAreByDesign() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(
            listOf(
                directConn("qq", 1000, 2000, process = "com.tencent.mobileqq", network = "udp"),
                directConn("ntp", 48, 48, process = "com.oplus.drs", network = "udp", destination = "120.25.115.20:123"),
                directConn("bad", 300, 100, process = "com.example.vpn", network = "udp", destination = "8.8.8.8:5353"),
            ),
        )
        val snapshot = ledger.snapshot.value
        // 设计内=QQ 通话 + NTP；bad 是意外 UDP 直连
        assertEquals(1048L, snapshot.byDesignUploadBytes)
        assertEquals(2048L, snapshot.byDesignDownloadBytes)
        assertEquals(300L, snapshot.netUploadBytes)
        assertEquals(100L, snapshot.netDownloadBytes)
        // Top 榜只进意外直连
        assertEquals(400L, snapshot.perAppBytes["com.example.vpn"])
        assertTrue(!snapshot.perAppBytes.containsKey("com.tencent.mobileqq"))
    }

    @Test
    fun tcpDirectIsNeverByDesign() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(directConn("t", 100, 100, process = "com.tencent.mobileqq", network = "tcp")))
        val snapshot = ledger.snapshot.value
        assertEquals(0L, snapshot.byDesignUploadBytes + snapshot.byDesignDownloadBytes)
        assertEquals(200L, snapshot.netUploadBytes + snapshot.netDownloadBytes)
    }

    @Test
    fun resetZeroesEverything() {
        val ledger = DirectTrafficLedger()
        ledger.onPoll(listOf(directConn("a", 100, 100, "com.example.a")))
        ledger.reset()
        assertEquals(0L, ledger.snapshot.value.totalBytes)
        assertTrue(ledger.snapshot.value.perAppBytes.isEmpty())
    }
}
