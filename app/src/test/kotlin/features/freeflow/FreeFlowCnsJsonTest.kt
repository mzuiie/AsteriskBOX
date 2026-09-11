// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import features.freeflow.compile.FreeFlowCompiler
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CNS 出站 JSON：proxy_key/udp_flag 与内核缺省相同时不落字段（I12 的 httpUDP 串检查天然通过）。 */
class FreeFlowCnsJsonTest {

    @Test
    fun defaultsAreOmitted() {
        val json = FreeFlowCompiler.buildCnsOutboundJson(
            server = "39.98.71.150",
            port = 50000,
            password = "x",
            masking = true,
            detourTag = "detour",
        ).jsonObject
        assertFalse(json.containsKey("proxy_key"))
        assertFalse(json.containsKey("udp_flag"))
        assertTrue(!json.toString().contains("httpUDP"))
        assertEquals("detour", json["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun customValuesAreWritten() {
        val json = FreeFlowCompiler.buildCnsOutboundJson(
            server = "39.98.71.150",
            port = 50000,
            password = "x",
            masking = false,
            detourTag = "",
            proxyKey = "MyKey",
            udpFlag = "MyFlag",
        ).jsonObject
        assertEquals("MyKey", json["proxy_key"]!!.jsonPrimitive.content)
        assertEquals("MyFlag", json["udp_flag"]!!.jsonPrimitive.content)
    }
}
