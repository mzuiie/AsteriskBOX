// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.domain

import kotlinx.serialization.Serializable

/**
 * 握手形态建模（真值表唯一门）。
 *
 * CONNECT 请求行三元组 = 目标形态 × path 后缀（原样拼接不转义）× 头集合（delHost = 无任何头）。
 * 合法性校验固化网关实测铁律（mtl28 真值表，与内核 15atp 手写握手语义一一对应）：
 * - T5 三头（Host + X-T5-Auth + baiduboxapp UA）→ 200 放行；
 * - 纯裸（delHost 无任何头）→ 200 放行；
 * - 半吊子形态（Auth 无 Host / 仅 UA / Go 默认 UA）→ 403 全拒，模型层直接判非法。
 * 族出站 JSON、探测报文、出站编辑器全部消费同一份形态，非法形态进不了任何一层。
 */
@Serializable
enum class TargetForm {
    /** CONNECT 行写原样域名（App 侧 http 出站默认，网关代解析）。 */
    Domain,

    /** CONNECT 行只写 IP:端口（内核 baidu 出站语义；"裸 IP 被拒"的网关须切回 Domain）。 */
    RawIp,
}

@Serializable
data class HeaderSpec(
    val name: String,
    val value: String,
)

@Serializable
data class HandshakeSpec(
    val targetForm: TargetForm = TargetForm.Domain,
    val delHost: Boolean = false,
    val headers: List<HeaderSpec> = emptyList(),
    val pathSuffix: String? = null,
)

/** 形态非法原因（null = 合法）。半吊子形态在此拦下，不让它进配置。 */
fun HandshakeSpec.illegalReason(): String? {
    headers.forEach { header ->
        if (header.name.isBlank() || header.name.hasControlChars()) return "头名非法：${header.name}"
        if (header.value.hasControlChars()) return "头值含控制字符：${header.name}"
    }
    if (delHost) {
        // 裸握手 = 无任何头；自定义 Host 与 Del Host 互斥（内核同语义：配了 Host 就不删）
        if (headers.isNotEmpty()) return "Del Host 与自定义头互斥（裸握手不能带头）"
    } else {
        val byName = headers.associate { it.name.lowercase() to it.value }
        if (byName["user-agent"]?.contains("go-http-client", ignoreCase = true) == true) {
            return "Go 默认 UA 是 403 实锤形态（mtl20 全灭根因），禁止出现"
        }
        val hasAuth = byName.containsKey("x-t5-auth")
        val hasHost = byName.containsKey("host")
        if (hasAuth && !hasHost) return "X-T5-Auth 必须伴随 Host（Auth 无 Host 是 403 实锤形态）"
        if (!hasAuth && !hasHost && byName.containsKey("user-agent")) {
            return "仅 UA 无 Host 是 403 实锤形态（半吊子）"
        }
    }
    pathSuffix?.let { path ->
        if (path.isEmpty()) return "path 后缀不能为空"
        if (!path.startsWith("@") && !path.startsWith("/")) return "path 后缀必须以 @ 或 / 开头"
        if (path.hasControlChars() || path.any { it == ' ' }) return "path 后缀含空白或控制字符（请求行注入风险）"
    }
    return null
}

/** 非实测但可能可用的形态提示（不拦截，仅 UI 警示）。 */
fun HandshakeSpec.unverifiedHint(): String? {
    if (delHost) return null
    val byName = headers.associate { it.name.lowercase() to it.value }
    return if (byName.containsKey("host") && !byName.containsKey("x-t5-auth") && !byName.containsKey("q-guid")) {
        "有 Host 无认证头：未实测形态，网关是否放行未知"
    } else {
        null
    }
}

/** 按头名取值（大小写不敏感，探测复用）。 */
fun HandshakeSpec.headerValue(name: String): String? =
    headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

/**
 * 手写 CONNECT 握手报文（内核 15atp `protocol/http/outbound.go` 同语义，探测专用）：
 * 请求行 = CONNECT <目标><path>，path 原样拼接不转义；Host 头 = 自定义或目标站；
 * delHost 整个不发 Host；不补默认 UA（Go-http-client UA = 403 实锤形态）。
 */
fun HandshakeSpec.connectRequestLine(target: String): String {
    val builder = StringBuilder("CONNECT ").append(target).append(pathSuffix.orEmpty()).append(" HTTP/1.1\r\n")
        .append("Proxy-Connection: Keep-Alive\r\n")
    if (!delHost) {
        builder.append("Host: ").append(headerValue("Host") ?: target).append("\r\n")
    }
    headers.forEach { header ->
        if (header.name.equals("Host", ignoreCase = true)) return@forEach
        builder.append(header.name).append(": ").append(header.value).append("\r\n")
    }
    builder.append("\r\n")
    return builder.toString()
}

private fun String.hasControlChars(): Boolean = any(Character::isISOControl)
