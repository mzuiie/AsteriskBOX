// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.modes.SingBoxModeRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1: 免流存续时最终路由失效=宁断不跳；全局选择器剔除 direct。 */
class SingBoxRouteFailClosedTest {

    private val channelTag = app.managedOutboundGroupSelectorTag(4, "免流网关")

    private fun freeFlowAppState(routeFinal: String) = AppState(
        outboundGroups = listOf(OutboundGroupState(id = 4, name = "免流网关", ownerKey = "freeflow")),
        routeFinal = routeFinal,
    )

    private fun compileRules(appState: AppState, available: Set<String>): List<JsonObject> =
        compileRoute(
            sourceRoute = null,
            appState = appState,
            availableOutboundTags = available,
            dnsEnabled = false,
            defaultDomainResolver = null,
        )["rules"]!!.jsonArray.map { element -> element.jsonObject }

    @Test
    fun freeFlowWithBrokenFinalRejectsEverything() {
        val rules = compileRules(freeFlowAppState(channelTag), setOf(APP_DIRECT_OUTBOUND, APP_GLOBAL_SELECTOR))
        val tail = rules.last()
        assertEquals("reject", tail["action"]!!.jsonPrimitive.content)
        assertEquals(1, tail.size)
        // 模式注入（全局/直连、clash_mode）全部停用
        assertTrue(rules.none { rule -> rule.containsKey("clash_mode") })
    }

    @Test
    fun healthyFreeFlowKeepsRuleFallbackWithoutReject() {
        val rules = compileRules(freeFlowAppState(channelTag), setOf(APP_DIRECT_OUTBOUND, APP_GLOBAL_SELECTOR, channelTag))
        assertTrue(rules.none { rule -> rule["action"]?.jsonPrimitive?.content == "reject" && rule.size == 1 })
        assertNotNull(rules.lastOrNull { rule -> rule.containsKey("clash_mode") })
    }

    @Test
    fun nonFreeFlowKeepsOriginalFallback() {
        val plain = AppState(routeFinal = channelTag)
        val rules = compileRules(plain, setOf(APP_DIRECT_OUTBOUND, APP_GLOBAL_SELECTOR))
        // 无免流组=回落 global 且保留模式注入（原行为，Direct 模式用户可用）
        assertTrue(rules.any { rule -> rule["outbound"]?.jsonPrimitive?.content == APP_GLOBAL_SELECTOR })
        assertTrue(rules.none { rule -> rule["action"]?.jsonPrimitive?.content == "reject" && rule.size == 1 })
    }

    @Test
    fun globalSelectorExcludesDirectWhenFreeFlowApplied() {
        val appState = AppState(
            outboundGroups = listOf(OutboundGroupState(id = 4, name = "免流网关", ownerKey = "freeflow")),
            outbounds = listOf(
                OutboundState(
                    id = 1,
                    groupId = 4,
                    remarks = "节点",
                    type = "socks",
                    json = "{\"type\":\"socks\",\"server\":\"1.2.3.4\",\"port\":1080}",
                ),
            ),
            singBoxMode = SingBoxModeRule,
        )
        val outbounds = compileOutbounds(buildJsonObject { }, appState)
        val selectorTags = outbounds.mapNotNull { element ->
            (element as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull
        }
        val globalSelector = outbounds
            .mapNotNull { element -> element as? JsonObject }
            .firstOrNull { obj -> obj["tag"]?.jsonPrimitive?.contentOrNull == APP_GLOBAL_SELECTOR }
        assertNotNull(globalSelector)
        val members = globalSelector!!["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse("直连出站不应出现在免流期的全局选择器", members.contains(APP_DIRECT_OUTBOUND))
        assertTrue(selectorTags.contains(APP_GLOBAL_SELECTOR))
    }
}
