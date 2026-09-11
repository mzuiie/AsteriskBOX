// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.usecase

import engine.singbox.config.encodeSingBoxJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 机场混淆 Host 注入（纯函数，随编译重注入）。节点形态覆盖：
 * - 普通 http 出站（百度/TPBox 通道族）：出站根 headers.Host
 * - v2ray ws 传输：transport.headers.Host
 * - v2ray http 传输：transport.headers.Host 与 transport.host 同值替换/注入
 *   （http1 线上 Host 以 headers.Host 为准，host 字段是另一写法，两者同值免歧义；
 *   都不存在时注入 transport.host）
 * - httpupgrade 传输：transport.host
 * 置空 = 清除伪装。grpc/quic 等无 Host 概念的形态不动；返回 null = 无需改动。
 */
internal object AirportObfHostPatcher {

    fun patch(type: String, json: String, obfHost: String): String? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        val transport = root["transport"] as? JsonObject
        val transportType = transport?.get("type")?.jsonPrimitive?.contentOrNull
        val patched: JsonObject = when {
            type == "http" -> {
                val headers = (root["headers"] as? JsonObject) ?: JsonObject(emptyMap())
                val nextHeaders = patchHostHeaderObject(headers, obfHost) ?: return null
                JsonObject(
                    buildMap {
                        root.forEach { (key, value) -> if (key != "headers") put(key, value) }
                        if (nextHeaders.isNotEmpty()) put("headers", nextHeaders)
                    },
                )
            }

            transport != null && transportType == "ws" -> {
                val headers = (transport["headers"] as? JsonObject) ?: JsonObject(emptyMap())
                val nextHeaders = patchHostHeaderObject(headers, obfHost) ?: return null
                val nextTransport = JsonObject(
                    buildMap {
                        transport.forEach { (key, value) -> if (key != "headers") put(key, value) }
                        if (nextHeaders.isNotEmpty()) put("headers", nextHeaders)
                    },
                )
                JsonObject(
                    buildMap {
                        root.forEach { (key, value) -> if (key != "transport") put(key, value) }
                        put("transport", nextTransport)
                    },
                )
            }

            transport != null && transportType == "http" -> {
                val host = obfHost.trim()
                val headers = transport["headers"] as? JsonObject
                val headerHost = headers?.get("Host")?.let { hostFieldString(it) }
                val fieldHost = hostFieldString(transport["host"])
                val effectiveHost = headerHost ?: fieldHost
                // 线上生效 Host 已是目标值（不管标量/数组/哪个字段承载）→ 无需改动
                if (host.isNotEmpty() && effectiveHost == host) return null
                if (host.isEmpty() && headerHost == null && fieldHost == null) return null
                val nextHeaders = headers?.let { patchHostHeaderObject(it, obfHost) }
                val hostChanged = if (host.isEmpty()) fieldHost != null else fieldHost != host
                val nextTransport = JsonObject(
                    buildMap {
                        transport.forEach { (key, value) ->
                            when (key) {
                                "headers" -> if (nextHeaders != null && nextHeaders.isNotEmpty()) put("headers", nextHeaders)
                                "host" -> if (host.isNotEmpty()) put("host", JsonPrimitive(host))
                                else -> put(key, value)
                            }
                        }
                        if (host.isNotEmpty() && "host" !in transport) put("host", JsonPrimitive(host))
                    },
                )
                JsonObject(
                    buildMap {
                        root.forEach { (key, value) -> if (key != "transport") put(key, value) }
                        put("transport", nextTransport)
                    },
                )
            }

            transport != null && transportType == "httpupgrade" -> {
                val current = transport["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val next = obfHost.trim()
                if (current == next) return null
                val nextTransport = JsonObject(
                    buildMap {
                        transport.forEach { (key, value) -> if (key != "host") put(key, value) }
                        if (next.isNotBlank()) put("host", JsonPrimitive(next))
                    },
                )
                JsonObject(
                    buildMap {
                        root.forEach { (key, value) -> if (key != "transport") put(key, value) }
                        put("transport", nextTransport)
                    },
                )
            }

            else -> return null
        }
        encodeSingBoxJson(patched)
    }.getOrNull()

    /** transport.host 字段（Listable 字符串：标量或数组）取首个元素。 */
    private fun hostFieldString(value: kotlinx.serialization.json.JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.firstOrNull()?.let { element -> (element as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    private fun patchHostHeaderObject(headers: JsonObject, obfHost: String): JsonObject? {
        val next = JsonObject(
            buildMap {
                headers.forEach { (key, value) -> if (key != "Host") put(key, value) }
                if (obfHost.isNotBlank()) put("Host", JsonPrimitive(obfHost.trim()))
            },
        )
        return if (next == headers) null else next
    }
}
