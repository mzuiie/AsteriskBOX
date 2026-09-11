// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow

import features.freeflow.usecase.AirportObfHostPatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 机场混淆 Host 注入：v2ray http 传输（headers.Host + host 字段）与既有形态回归。 */
class FreeFlowAirportObfHostPatchTest {

    private val vmessHttpTransport = """
        {
          "type": "vmess",
          "server": "cu.hylab.im",
          "server_port": 57703,
          "uuid": "37ecc0bd-709c-4b06-ae1c-15b300b8f4f4",
          "security": "auto",
          "transport": {
            "type": "http",
            "path": "/",
            "headers": { "Host": ["gw.alicdn.com"] }
          }
        }
    """.trimIndent()

    private fun patchOrNull(type: String, json: String, host: String): JsonObject? =
        AirportObfHostPatcher.patch(type, json, host)
            ?.let { Json.parseToJsonElement(it).jsonObject }

    private fun patch(type: String, json: String, host: String): JsonObject =
        patchOrNull(type, json, host) ?: error("expected patched output")

    @Test
    fun vmessHttpTransportHeadersHostReplaced() {
        val patched = patch("vmess", vmessHttpTransport, "dm.toutiao.com")
        val transport = patched["transport"]!!.jsonObject
        assertEquals("http", transport["type"]!!.jsonPrimitive.content)
        assertEquals("dm.toutiao.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertEquals("dm.toutiao.com", transport["host"]!!.jsonPrimitive.content)
        assertEquals("cu.hylab.im", patched["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun vmessHttpTransportHostListFieldReplaced() {
        val json = """
            {
              "type": "vmess",
              "server": "45.192.202.19",
              "server_port": 11180,
              "uuid": "u",
              "security": "auto",
              "transport": { "type": "http", "path": "/", "host": ["dm.toutiao.com"] }
            }
        """.trimIndent()
        val transport = patch("vmess", json, "gw.alicdn.com")["transport"]!!.jsonObject
        assertEquals("gw.alicdn.com", transport["host"]!!.jsonPrimitive.content)
    }

    @Test
    fun vmessHttpTransportInjectsHostFieldWhenAbsent() {
        val json = """
            {
              "type": "vmess",
              "server": "s",
              "server_port": 1,
              "uuid": "u",
              "security": "auto",
              "transport": { "type": "http", "path": "/" }
            }
        """.trimIndent()
        val transport = patch("vmess", json, "dm.toutiao.com")["transport"]!!.jsonObject
        assertEquals("dm.toutiao.com", transport["host"]!!.jsonPrimitive.content)
    }

    @Test
    fun vmessHttpTransportClearRemovesHostEverywhere() {
        val transport = patch("vmess", vmessHttpTransport, "")["transport"]!!.jsonObject
        assertEquals("http", transport["type"]!!.jsonPrimitive.content)
        assertEquals(false, transport.toString().contains("gw.alicdn.com"))
        assertEquals(false, transport.toString().contains("dm.toutiao.com"))
    }

    @Test
    fun vmessHttpTransportUnchangedReturnsNull() {
        assertNull(patchOrNull("vmess", vmessHttpTransport, "gw.alicdn.com"))
    }

    @Test
    fun wsTransportHeadersHostReplaced() {
        val json = """
            {
              "type": "vmess",
              "server": "s",
              "server_port": 2,
              "uuid": "u",
              "security": "auto",
              "transport": { "type": "ws", "path": "/", "headers": { "Host": ["old.example.com"] } }
            }
        """.trimIndent()
        val transport = patch("vmess", json, "dm.toutiao.com")["transport"]!!.jsonObject
        assertEquals(
            "dm.toutiao.com",
            transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun plainHttpOutboundRootHeadersReplaced() {
        val json = """
            {
              "type": "http",
              "server": "220.181.7.1",
              "server_port": 443,
              "headers": { "Host": "153.3.236.22:443", "X-T5-Auth": "683556433" }
            }
        """.trimIndent()
        val headers = patch("http", json, "dm.toutiao.com")["headers"]!!.jsonObject
        assertEquals("dm.toutiao.com", headers["Host"]!!.jsonPrimitive.content)
        assertEquals("683556433", headers["X-T5-Auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun grpcTransportUntouched() {
        val json = """
            {
              "type": "vmess",
              "server": "s",
              "server_port": 3,
              "uuid": "u",
              "security": "auto",
              "transport": { "type": "grpc", "service_name": "svc" }
            }
        """.trimIndent()
        assertNull(patchOrNull("vmess", json, "dm.toutiao.com"))
    }
}
