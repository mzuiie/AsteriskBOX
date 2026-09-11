// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import features.freeflow.compile.FfSimCarrier
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.compile.carrierNodeRank
import features.freeflow.compile.ffSimCarrierFromOperator
import features.freeflow.compile.restoreChannelCargo
import features.freeflow.compile.takeChannelCargoSnapshot
import features.freeflow.compile.withChannelNodesCarrierOrdered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeFlowCarrierOrderTest {

    @Test
    fun carrierFromOperatorMapsMnc() {
        assertEquals(FfSimCarrier.Unicom, ffSimCarrierFromOperator("46001"))
        assertEquals(FfSimCarrier.Unicom, ffSimCarrierFromOperator("46009"))
        assertEquals(FfSimCarrier.Telecom, ffSimCarrierFromOperator("46011"))
        assertEquals(FfSimCarrier.Mobile, ffSimCarrierFromOperator("46008"))
        assertEquals(FfSimCarrier.Unknown, ffSimCarrierFromOperator(""))
        assertEquals(FfSimCarrier.Unknown, ffSimCarrierFromOperator("46099"))
    }

    @Test
    fun rankFollowsCarrierPriority() {
        // 联通卡: 联通0 电信1 移动2；电信卡: 电信0 联通1 移动2；移动卡: 移动0 联通1 电信2
        assertEquals(0, carrierNodeRank("[苏州]联通", FfSimCarrier.Unicom))
        assertEquals(1, carrierNodeRank("[南京]电信", FfSimCarrier.Unicom))
        assertEquals(2, carrierNodeRank("[广州]移动", FfSimCarrier.Unicom))
        assertEquals(0, carrierNodeRank("[南京]电信", FfSimCarrier.Telecom))
        assertEquals(1, carrierNodeRank("[苏州]联通", FfSimCarrier.Telecom))
        assertEquals(0, carrierNodeRank("百度直连-南京移动", FfSimCarrier.Mobile))
        assertEquals(3, carrierNodeRank("WAP网关", FfSimCarrier.Unicom))
        // 识别不出按默认序（联通→电信→移动）
        assertEquals(0, carrierNodeRank("[苏州]联通", FfSimCarrier.Unknown))
        assertEquals(1, carrierNodeRank("[南京]电信", FfSimCarrier.Unknown))
    }

    @Test
    fun channelMembersSortStableAndOtherGroupsUntouched() {
        val state = AppState(
            outboundGroups = listOf(
                OutboundGroupState(id = 1, name = "免流网关", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
                OutboundGroupState(id = 2, name = "机场节点"),
            ),
            outbounds = listOf(
                OutboundState(id = 11, groupId = 1, remarks = "[广州]移动", type = "http", json = "{}"),
                OutboundState(id = 12, groupId = 1, remarks = "[苏州]联通", type = "http", json = "{}"),
                OutboundState(id = 13, groupId = 1, remarks = "[南京]电信", type = "http", json = "{}"),
                OutboundState(id = 14, groupId = 1, remarks = "手添", type = "http", json = "{}"),
                OutboundState(id = 21, groupId = 2, remarks = "[苏州]联通", type = "vmess", json = "{}"),
            ),
        )
        val sorted = state.withChannelNodesCarrierOrdered("baidu_t5", FfSimCarrier.Telecom)
        assertEquals(
            listOf("[南京]电信", "[苏州]联通", "[广州]移动", "手添"),
            sorted.outbounds.filter { it.groupId == 1 }.map { it.remarks },
        )
        // 非通道组成员原位不动
        assertEquals(21, sorted.outbounds.last { it.groupId == 2 }.id)
        // 彩信族不排序
        assertEquals(state, state.withChannelNodesCarrierOrdered("mms", FfSimCarrier.Mobile))
    }

    @Test
    fun channelCargoRestoreReidsToAvoidCollisions() {
        val state = AppState(
            outboundGroups = listOf(
                OutboundGroupState(id = 1, name = "免流网关", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
            ),
            outbounds = listOf(
                OutboundState(id = 500, groupId = 1, remarks = "内置货", type = "http", json = "{}"),
            ),
            nextOutboundId = 501,
        )
        val cargo = listOf(
            OutboundState(
                id = 500,
                groupId = 1,
                remarks = "订阅货",
                type = "http",
                json = "{\"type\":\"http\",\"tag\":\"outbound_500_订阅货\"}",
            ),
        )
        val restored = state.restoreChannelCargo(cargo)
        val ids = restored.outbounds.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val member = restored.outbounds.first { it.remarks == "订阅货" }
        assertTrue(member.id != 500)
        assertTrue(member.json.contains("outbound_" + member.id + "_订阅货"))
        assertFalse(member.json.contains("outbound_500_"))
        assertEquals(501, member.id)
        assertEquals(502, restored.nextOutboundId)
    }

    @Test
    fun cargoSnapshotOnlyTakesUnmarkedMembers() {
        val state = AppState(
            outboundGroups = listOf(
                OutboundGroupState(id = 1, name = "免流网关", ownerKey = FreeFlowCompiler.FreeFlowOwnerKey),
            ),
            outbounds = listOf(
                OutboundState(
                    id = 11,
                    groupId = 1,
                    remarks = "内置货",
                    type = "http",
                    json = "{}",
                    meta = "{\"ff\":{\"family\":\"baidu_t5\",\"source\":\"Builtin\"}}",
                ),
                OutboundState(id = 12, groupId = 1, remarks = "编辑器手添", type = "http", json = "{}"),
                OutboundState(id = 13, groupId = 1, remarks = "订阅拉取", type = "http", json = "{}"),
            ),
        )
        val cargo = state.takeChannelCargoSnapshot()
        assertEquals(listOf("编辑器手添", "订阅拉取"), cargo.map { it.remarks })
    }
}
