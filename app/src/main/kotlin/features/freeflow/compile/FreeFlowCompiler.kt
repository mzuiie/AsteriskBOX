// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.compile

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.ResourceFileKind
import app.SingBoxDnsRuleMatchState
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeDefault
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleLogicalModeAnd
import app.SingBoxRouteRuleTypeDefault
import app.SingBoxRouteRuleTypeLogical
import app.managedBundledRuleSetTag
import app.managedDnsServerTag
import app.managedOutboundGroupSelectorTag
import app.managedOutboundTag
import app.nextAvailableOutboundGroupId
import app.withPrunedDnsServerReferences
import app.withRemovedManagedOutboundTags
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.MtlCnsOutboundType
import features.freeflow.domain.AirportSpec
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowFamily
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.FfWapPort
import features.freeflow.domain.FfWapRemarks
import features.freeflow.domain.FfWapServer
import features.freeflow.domain.GatewayProfile
import features.freeflow.domain.HeaderSpec
import features.freeflow.domain.HandshakeSpec
import features.freeflow.domain.MmsFamily
import features.freeflow.domain.NodeSource
import features.freeflow.domain.NodeSpec
import features.freeflow.domain.UdpPolicy
import features.freeflow.domain.freeFlowBuiltinNodes
import features.freeflow.domain.freeFlowFamilyByKey
import features.freeflow.domain.illegalReason
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 免流编译器：FreeFlowProfile（声明档）+ 基础 AppState → sing-box 配置产物，
 * 纯函数、全 JVM 可测。产物带 ownerKey/meta 结构化身份；编译完成跑领域铁律
 * 自检（违反即失败不落盘），任何真机新发现落成一条断言而不是一条注释。
 */
object FreeFlowCompiler {

    /** 冻结契约：免流产物归属标记（组身份走它，不依赖组名）。 */
    const val FreeFlowOwnerKey = "freeflow"

    /** 路由/DNS 规则的人读标签前缀（身份以 ownerKey/区间管理为准）。 */
    const val FreeFlowRuleRemarksPrefix = "ff_"

    /** 旧版（features/mtl）规则前缀，strip 时一并清理。 */
    const val LegacyRuleRemarksPrefix = "mtl_"

    const val FreeFlowChannelGroupName = "免流网关"
    const val FreeFlowCnsGroupName = "CNS 隧道"
    const val FreeFlowAirportGroupName = "机场节点"

    internal const val FreeFlowFakeIpRemarks = "ff_fakeip"

    // 旧版识别标记（一次性迁移用）
    internal const val LegacyChannelGroupName = "免流网关节点"
    internal const val LegacyCnsRemarks = "CNS-UDP隧道"

    private val FfPrivateIpCidrs = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
        "224.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fe80::/10", "fd00::/8", "ff00::/8",
    )

    // NTP 服务域（系统对时真实解析；专项规则排在 FakeIP 入口之前、兜底空应答之后，见 I5⑧）
    private val FfNtpDomainSuffixes = listOf(
        "ntp.org", "pool.ntp.org", "time.apple.com", "time.windows.com",
        "time.android.com", "time.cloudflare.com", "ntp.aliyun.com",
        "time1.cloud.tencent.com", "ntp.tuna.tsinghua.edu.cn",
    )

    // 网关白名单必拒的目标，本地拦掉降噪（sfm v11.1 清单：heytap 全家 + 搜狗 + QQ输入法云）
    internal val TelemetryDomainSuffixes = listOf(
        "comfylink.com", "kuiniuca.com", "starrydyn.com", "ndcpp.com", "sjxydc.com",
        "qrstuvwxyzab.com", "tfogc.com", "z1cdn.com", "mountaintoys.cn", "ctrmi.cn",
        "appcenter.ms", "rmonitor.qq.com", "get.sogou.com",
        "sginput.qq.com",
    )
    internal val TelemetryDomainKeywords = listOf("push-ads", "obus", "datasec")

    // UDP 白名单按包名放行（微信/QQ 通话走系统分配 IP，域名规则兜不住）
    internal val UdpWhitelistPackageNames = listOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
    )
    internal val UdpWhitelistDomainSuffixes = listOf("qq.com", "wechat.com")

    /** 编译上下文（保留扩展位；bypass 附加输入已随 ff_bypass_direct 一并删除）。 */
    class Context

    enum class Flag {
        /** 免流模式强制关 IPv6：TUN 不接管 v6 时硬编码 v6 的应用会物理旁路（I5③）。 */
        ForceDisableIpv6,

        /** ROOT 模式强制开「IPv6 禁用器」（sysctl 物理禁 v6，五模式全覆盖）：v6 进不了内核的
         *  模式（如 TPROXY）靠它堵 B 站等 v6 优先应用的物理旁路，宁断不跳（I5③）。 */
        ForceRootIpv6Disabler,

        /** fakeip 缓存强制开：消重启后 missing fakeip record 刷屏（I5⑨）。 */
        ForceStoreFakeIp,

        /** 本地 DNS 服务强制开（FakeIP 前提）。 */
        ForceLocalDns,
    }

    data class Artifacts(
        val groups: List<OutboundGroupState>,
        val outbounds: List<OutboundState>,
        val routeRules: List<SingBoxRouteRuleState>,
        val dnsServers: List<SingBoxDnsServerState>,
        val dnsRules: List<SingBoxDnsRuleState>,
        val routeFinal: String,
        val flags: Set<Flag>,
    )

    enum class InvariantId {
        ChannelHandshake,
        FakeIpServer,
        QuicDoublePort,
        Ipv6Guard,
        UdpReject,
        Telemetry,
        ExplicitPrivateCidr,
        NtpBeforeFakeIpEntry,
        CnsIsolation,
        CnsNoUdpFlag,
        SplitTarget,
        AgriBankCompat,
    }

    sealed interface Result {
        data class Success(val artifacts: FreeFlowCompiler.Artifacts) : Result
        data class Failed(val invariant: InvariantId, val reason: String) : Result
    }

    /** 纯机场模式派生组名：源机场组各拆一个国内/国外 selector。 */
    internal fun derivedAirportGroupNames(airportName: String): Pair<String, String> =
        airportName.plus("·国内") to airportName.plus("·国外")

    /** 通道组 selector tag（产物内组 id 确定）。 */
    fun channelSelectorTag(groupId: Int): String =
        managedOutboundGroupSelectorTag(groupId, FreeFlowCompiler.FreeFlowChannelGroupName)

    // ---------------------------------------------------------------- 出站 JSON

    /** 通道成员出站 JSON：头序按握手声明序（字节级固定由内核/手写握手消费侧保证）。 */
    fun buildChannelOutboundJson(
        handshake: HandshakeSpec,
        server: String,
        serverPort: Int,
        wapTag: String? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("http"))
            put("server", JsonPrimitive(server))
            put("server_port", JsonPrimitive(serverPort))
            if (handshake.delHost) put("del_host", JsonPrimitive(true))
            handshake.pathSuffix?.let { path -> put("path", JsonPrimitive(path)) }
            if (!handshake.delHost && handshake.headers.isNotEmpty()) {
                put(
                    "headers",
                    JsonObject(
                        buildMap {
                            handshake.headers.forEach { header ->
                                put(header.name, JsonPrimitive(header.value))
                            }
                        },
                    ),
                )
            }
            // 彩信直连经运营商 WAP 网关中转（clash 语义 dialer-proxy），与裸格式不冲突
            if (wapTag != null) put("detour", JsonPrimitive(wapTag))
        },
    )

    /** CNS UDP 隧道出站 JSON（TCP 会话可骑免流通道；独立组防 circular dependency，I12）。 */
    fun buildCnsOutboundJson(
        server: String,
        port: Int,
        password: String,
        masking: Boolean,
        detourTag: String,
        maskHost: String = "",
        proxyKey: String = "Meng",
        udpFlag: String = "httpUDP",
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(MtlCnsOutboundType))
            put("server", JsonPrimitive(server))
            put("server_port", JsonPrimitive(port))
            put("password", JsonPrimitive(password))
            if (masking) put("masking", JsonPrimitive(true))
            if (maskHost.isNotBlank()) put("mask_host", JsonPrimitive(maskHost.trim()))
            // proxy_key/udp_flag 仅在与内核缺省(Meng/httpUDP)不同时落 JSON：
            // 内核自己会用缺省值，默认不写字段保证 I12(httpUDP 串)检查天然通过
            val normalizedKey = proxyKey.trim()
            if (normalizedKey.isNotEmpty() && normalizedKey != "Meng") {
                put("proxy_key", JsonPrimitive(normalizedKey))
            }
            val normalizedFlag = udpFlag.trim()
            if (normalizedFlag.isNotEmpty() && normalizedFlag != "httpUDP") {
                put("udp_flag", JsonPrimitive(normalizedFlag))
            }
            // detour 留空 = 不前置免流通道，CNS 直连
            if (detourTag.isNotBlank()) put("detour", JsonPrimitive(detourTag))
        },
    )

    internal fun outboundMeta(familyKey: String, source: NodeSource): String =
        buildJsonObject {
            putJsonObject("ff") {
                put("family", JsonPrimitive(familyKey))
                put("source", JsonPrimitive(source.name))
            }
        }.toString()

    // ---------------------------------------------------------------- 编译

    fun compile(base: AppState, profile: FreeFlowProfile, context: Context = Context()): Result {
        val family = freeFlowFamilyByKey(profile.channel.familyKey)
        var groupId = base.nextAvailableOutboundGroupId()
        var nextId = base.nextOutboundId.coerceAtLeast(
            (base.outbounds.maxOfOrNull(OutboundState::id) ?: 0) + 1,
        )

        val groups = mutableListOf<OutboundGroupState>()
        val outbounds = mutableListOf<OutboundState>()

        // ---- 通道组（成员 = 族内置库整组重建 + 档内非内置货随族保留（手添/订阅，NodeSource 语义）；
        //      旧实现"档内非空即整组替换"，换族会把自定义节点连手删掉；Builtin 货一律随族重建）。
        //      纯机场模式不建通道组：免流通道整体不参与（选择器页不再显示多余条目，切回时族内置重建）。
        val buildChannel = profile.split.mode != features.freeflow.domain.SplitMode.AirportOnly
        val channelNodes = if (buildChannel) {
            freeFlowBuiltinNodes(profile.channel.familyKey) +
                profile.channel.nodes.filterNot { node -> node.source == NodeSource.Builtin }
        } else {
            emptyList()
        }
        val wapTag = if (buildChannel && profile.channel.familyKey == MmsFamily.key) {
            val tag = managedOutboundTag(nextId, FfWapRemarks)
            outbounds += OutboundState(
                id = nextId,
                groupId = groupId,
                remarks = FfWapRemarks,
                type = "http",
                json = buildChannelOutboundJson(HandshakeSpec(), FfWapServer, FfWapPort).toString(),
                meta = outboundMeta(profile.channel.familyKey, NodeSource.Builtin),
            )
            nextId += 1
            tag
        } else {
            null
        }
        channelNodes.forEach { node ->
            node.handshake.illegalReason()?.let {
                return Result.Failed(InvariantId.ChannelHandshake, "${node.remarks}: $it")
            }
            val json = buildChannelOutboundJson(node.handshake, node.server, node.serverPort, wapTag)
            outbounds += OutboundState(
                id = nextId,
                groupId = groupId,
                remarks = node.remarks,
                type = "http",
                json = json.toString(),
                meta = outboundMeta(profile.channel.familyKey, node.source),
            )
            nextId += 1
        }
        val channelTag = channelSelectorTag(groupId)
        if (buildChannel) {
            groups += OutboundGroupState(
                id = groupId,
                name = FreeFlowCompiler.FreeFlowChannelGroupName,
                url = profile.channel.subscriptionUrl,
                userAgent = if (profile.channel.subscriptionUrl.isNotBlank()) {
                    features.freeflow.domain.FreeFlowSubscriptionUserAgent
                } else {
                    app.DefaultOutboundSubscriptionUserAgent
                },
                updateViaProxy = profile.channel.subscriptionUrl.isNotBlank(),
                ownerKey = FreeFlowOwnerKey,
            )
            groupId += 1
        }

        // ---- CNS 组（独立组，防组 selector 互指 circular dependency FATAL；纯机场模式不建）
        var cnsTag: String? = null
        // ViaCns 前置条件（人话报错，不等不变量兜底；纯机场不走 CNS 无此要求）
        if (profile.udp == UdpPolicy.ViaCns && profile.cnsNodes.isEmpty() && buildChannel) {
            return Result.Failed(
                InvariantId.UdpReject,
                "UDP 策略为「走 CNS 隧道」但还没有 CNS 节点：请先添加 CNS 节点",
            )
        }
        if (buildChannel && profile.cnsNodes.isNotEmpty()) {
            val cnsGroupId = groupId
            groupId += 1
            profile.cnsNodes.forEach { node ->
                val cnsJson = buildCnsOutboundJson(
                    server = node.server,
                    port = node.port,
                    password = node.password,
                    masking = node.masking,
                    detourTag = if (node.prependFree) channelTag else "",
                    maskHost = node.maskHost,
                    proxyKey = node.proxyKey,
                    udpFlag = node.udpFlag,
                )
                outbounds += OutboundState(
                    id = nextId,
                    groupId = cnsGroupId,
                    remarks = node.name.ifBlank { FreeFlowCompiler.FreeFlowCnsGroupName },
                    type = MtlCnsOutboundType,
                    json = cnsJson.toString(),
                    meta = outboundMeta(profile.channel.familyKey, NodeSource.Manual),
                )
                nextId += 1
            }
            cnsTag = managedOutboundGroupSelectorTag(cnsGroupId, FreeFlowCompiler.FreeFlowCnsGroupName)
            groups += OutboundGroupState(
                id = cnsGroupId,
                name = FreeFlowCompiler.FreeFlowCnsGroupName,
                ownerKey = FreeFlowOwnerKey,
            )
        }

        // ---- 机场组 reconcile：base 里已有的 owned 机场组透传保留，缺失的新建空组。
        //      纯机场模式不建源组（派生组即成员载体）；其它模式缺失时若纯机场时期的派生组还在
        //      base（切回播种），把「机场名·国内」成员搬回源组，节点无损回填。
        val ownedAirportGroups = base.outboundGroups
            .filter { group -> group.ownerKey == FreeFlowOwnerKey }
        profile.airports.forEach { airport ->
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly) return@forEach
            if (ownedAirportGroups.none { group -> group.name == airport.name }) {
                groups += OutboundGroupState(
                    id = groupId,
                    name = airport.name,
                    url = airport.url,
                    userAgent = if (airport.url.isNotBlank()) {
                        features.freeflow.domain.FreeFlowSubscriptionUserAgent
                    } else {
                        app.DefaultOutboundSubscriptionUserAgent
                    },
                    updateViaProxy = airport.url.isNotBlank(),
                    ownerKey = FreeFlowOwnerKey,
                )
                groupId += 1
                // 切回播种：纯机场时期源组被清掉，成员在「机场名·国内」里——搬回源组，节点无损
                val seedGroupId = base.outboundGroups
                    .firstOrNull { group ->
                        group.ownerKey == FreeFlowOwnerKey &&
                            group.name == derivedAirportGroupNames(airport.name).first
                    }
                    ?.id
                if (seedGroupId != null) {
                    base.outbounds
                        .filter { outbound -> outbound.groupId == seedGroupId }
                        .forEach { member ->
                            outbounds += member.copy(groupId = groupId - 1)
                        }
                }
            }
        }
        val ownedGroupTagByName: (String) -> String? = { name ->
            ownedAirportGroups.firstOrNull { group -> group.name == name }
                ?.let { group -> managedOutboundGroupSelectorTag(group.id, group.name) }
                ?: groups.firstOrNull { group -> group.name == name }
                    ?.let { group -> managedOutboundGroupSelectorTag(group.id, group.name) }
        }
        val airportTagOf: (AirportSpec) -> String? = { airport -> ownedGroupTagByName(airport.name) }

        // ---- 纯机场派生组：「{机场名}·国内」「{机场名}·国外」各一个 selector，透传保留；
        //      首建时复制源机场组成员并挂同一订阅（节点立即可选，后续订阅拉取照常整组替换）
        var derivedCnTag: String? = null
        var derivedFinalTag: String? = null
        if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly) {
            val activeSpec = profile.split.activeAirport
                ?.let { name -> profile.airports.firstOrNull { airport -> airport.name == name } }
                ?: profile.airports.firstOrNull()
            if (activeSpec != null) {
                val (cnName, finalName) = derivedAirportGroupNames(activeSpec.name)
                val knownNames = ownedAirportGroups.mapTo(mutableSetOf()) { group -> group.name } +
                    groups.mapTo(mutableSetOf()) { group -> group.name }
                if (cnName !in knownNames || finalName !in knownNames) {
                    val sourceMembers = base.outboundGroups
                        .firstOrNull { group -> group.ownerKey == FreeFlowOwnerKey && group.name == activeSpec.name }
                        ?.let { group -> base.outbounds.filter { outbound -> outbound.groupId == group.id } }
                        .orEmpty()
                    listOf(cnName, finalName).forEach { name ->
                        if (name in knownNames) return@forEach
                        val derivedGroupId = groupId
                        groupId += 1
                        groups += OutboundGroupState(
                            id = derivedGroupId,
                            name = name,
                            url = activeSpec.url,
                            userAgent = if (activeSpec.url.isNotBlank()) {
                                features.freeflow.domain.FreeFlowSubscriptionUserAgent
                            } else {
                                app.DefaultOutboundSubscriptionUserAgent
                            },
                            updateViaProxy = activeSpec.url.isNotBlank(),
                            ownerKey = FreeFlowOwnerKey,
                        )
                        sourceMembers.forEach { member ->
                            outbounds += OutboundState(
                                id = nextId,
                                groupId = derivedGroupId,
                                remarks = member.remarks,
                                type = member.type,
                                json = member.json,
                                meta = member.meta,
                            )
                            nextId += 1
                        }
                    }
                }
                derivedCnTag = ownedGroupTagByName(cnName)
                derivedFinalTag = ownedGroupTagByName(finalName)
            }
        }

        // ---- DNS 产物：默认上游加密化（udp 223.5.5.5 → DoH）+ FakeIP + 空应答族
        val upgradedServers = base.dnsServers.map { server ->
            if (server.type == "udp" && server.server == "223.5.5.5") server.copy(type = "https") else server
        }
        val fakeIpId = base.nextDnsServerId.coerceAtLeast(
            (base.dnsServers.maxOfOrNull(SingBoxDnsServerState::id) ?: 0) + 1,
        )
        val fakeIpTag = managedDnsServerTag(fakeIpId, FreeFlowFakeIpRemarks)
        val dnsServers = upgradedServers + SingBoxDnsServerState(
            id = fakeIpId,
            remarks = FreeFlowFakeIpRemarks,
            type = "fakeip",
            inet4Range = "28.0.0.0/8",
            inet6Range = "fc00::/18",
        )
        var nextDnsRuleId = base.nextDnsRuleId.coerceAtLeast(
            (base.dnsRules.maxOfOrNull(SingBoxDnsRuleState::id) ?: 0) + 1,
        )
        val directServer = upgradedServers.firstOrNull { server ->
            server.type != "fakeip" && server.server.isNotBlank()
        }
        val infraDomains = collectInfraDomains(base, profile)
        // 免流模式 DNS 由编译器全权接管（I4 落地）：基础 DNS 分流规则整体忽略。
        // 忽略"国内域→直连 DNS"的真实解析规则是 UDP 分流的前提——否则国内域名拿到真实 IP，
        // UDP 连接没有 FakeIP 域名映射，geosite 域名分流对国内目标失效（真机 WebRTC 实锤：
        // 国外 STUN 通、国内 STUN 全挂的对称性破缺）。忽略"国外域→代理 DNS"规则的原因见
        // git 历史（DoT 骑免流网关被白名单拒）。真实解析只保留 NTP 与基础设施域（编译器规则）。
        val baseDnsRules = emptyList<SingBoxDnsRuleState>()
        val dnsRules = buildList {
            addAll(baseDnsRules)
            // type65(HTTPS)/SVCB/ANY 不在 FakeIP A/AAAA 范围内，漏到真实解析必死；空应答逼回落 A（I4）
            add(
                SingBoxDnsRuleState(
                    id = nextDnsRuleId++,
                    remarks = "ff_type65_any_empty",
                    matches = listOf(
                        SingBoxDnsRuleMatchState(field = "query_type", values = listOf("HTTPS", "SVCB", "ANY")),
                    ),
                    action = "predefined",
                    rcode = "NOERROR",
                    type = SingBoxDnsRuleTypeDefault,
                ),
            )
            // FakeIP 兜底：剩余查询类型（SRV/TXT 等）一律空应答，消真实解析死循环刷屏（I4）
            add(
                SingBoxDnsRuleState(
                    id = nextDnsRuleId++,
                    remarks = "ff_fakeip_fallback_empty",
                    matches = listOf(
                        SingBoxDnsRuleMatchState(field = "query_type", values = listOf("A", "AAAA")),
                    ),
                    invert = true,
                    action = "predefined",
                    rcode = "NOERROR",
                    type = SingBoxDnsRuleTypeDefault,
                ),
            )
            // IPv6 防漏：AAAA 空应答，应用拿不到 v6 地址直接走 v4 进 FakeIP（I5③）
            add(
                SingBoxDnsRuleState(
                    id = nextDnsRuleId++,
                    remarks = "ff_aaaa_empty",
                    matches = listOf(
                        SingBoxDnsRuleMatchState(field = "query_type", values = listOf("AAAA")),
                    ),
                    action = "predefined",
                    rcode = "NOERROR",
                    type = SingBoxDnsRuleTypeDefault,
                ),
            )
            // NTP 专项：真实解析取时；必须排在 FakeIP 入口之前（A/AAAA 才不被假 IP 截走）
            if (directServer != null) {
                add(
                    SingBoxDnsRuleState(
                        id = nextDnsRuleId++,
                        remarks = "ff_ntp",
                        matches = listOf(
                            SingBoxDnsRuleMatchState(field = "domain_suffix", values = FfNtpDomainSuffixes),
                        ),
                        action = "route",
                        server = managedDnsServerTag(directServer.id, directServer.remarks),
                        type = SingBoxDnsRuleTypeDefault,
                    ),
                )
            }
            // 基础设施域专项（方案 A2）：机场/CNS/网关订阅服务器域强制走模板加密上游，防 FakeIP 截走
            if (directServer != null && infraDomains.isNotEmpty()) {
                add(
                    SingBoxDnsRuleState(
                        id = nextDnsRuleId++,
                        remarks = "ff_infra_dns",
                        matches = listOf(
                            SingBoxDnsRuleMatchState(field = "domain_suffix", values = infraDomains.toList()),
                        ),
                        action = "route",
                        server = managedDnsServerTag(directServer.id, directServer.remarks),
                        type = SingBoxDnsRuleTypeDefault,
                    ),
                )
            }
            // FakeIP 入口：全部 A/AAAA 进 FakeIP；免流场景无直连解析需求（I4）
            add(
                SingBoxDnsRuleState(
                    id = nextDnsRuleId++,
                    remarks = "ff_fakeip_entry",
                    matches = listOf(
                        SingBoxDnsRuleMatchState(field = "query_type", values = listOf("A", "AAAA")),
                    ),
                    action = "route",
                    server = fakeIpTag,
                    rewriteTtl = "0",
                    type = SingBoxDnsRuleTypeDefault,
                ),
            )
        }

        // ---- 路由产物：防跳十诫有序链（顺序确定性，见设计 §5.1）
        var nextRuleId = base.nextRouteRuleId.coerceAtLeast(
            (base.routeRules.maxOfOrNull(SingBoxRouteRuleState::id) ?: 0) + 1,
        )
        val directTag = APP_DIRECT_OUTBOUND
        val routeRules = buildList {
            // 私网直连：显式 ip_cidr，v6 段避开 FakeIP 池 fc00::/18（I5⑦）
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_private_ip",
                    ipCidr = FfPrivateIpCidrs,
                    action = SingBoxRouteRuleActionRoute,
                    outbound = directTag,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // 防跳：853 DoT/DoQ reject
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_block_dot",
                    network = listOf("tcp", "udp"),
                    port = listOf("853"),
                    action = SingBoxRouteRuleActionReject,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // IPv6 防跳：真实 v6 目标全拒，宁断不跳（FakeIP 连接按域名匹配路由不受影响，I5③）
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_block_ipv6",
                    ipVersion = 6,
                    action = SingBoxRouteRuleActionReject,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // QUIC 双端口拦截：免流通道只有 TCP，逼应用回落（80 必须也封，h3 备用端口，I5①）
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_block_quic",
                    network = listOf("udp"),
                    port = listOf("80", "443"),
                    action = SingBoxRouteRuleActionReject,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // 农行兼容（实验）：农行硬编码 IP:441/442 直连自己的服务器，网关白名单按 IP:端口对
            // 放行、任意非常规端口对一律 403（2026-09-08 PC 真值表实证：同 IP :443 过 / :441 拒，
            // 三网关三种握手形态一致；自家 CNS 中继在白名单内）。clnc/ZJL 当年可用即因它们的
            // "节点"本就是 GET 伪装头中继——CNS 出站是同款载体（目标藏协议头，由中继拨真实目标，
            // 出口国内）。TCP 改走 CNS 组，排在遥测拦截之前；QUIC/IPv6 防跳拒绝不豁免。
            // 无 CNS 时不生成规则（软依赖；机场/王卡腿经用户否决——根治=请服务商把农行端口加白名单）。
            if (profile.guards.agriBankCompatEnabled &&
                profile.split.mode != features.freeflow.domain.SplitMode.AirportOnly &&
                cnsTag != null
            ) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_agribank_cns",
                        network = listOf("tcp"),
                        packageName = features.freeflow.domain.AgriBankPackageNames,
                        action = SingBoxRouteRuleActionRoute,
                        outbound = cnsTag.orEmpty(),
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            // 遥测 P2P 本地拦截：消 302/503 重试刷屏、省免流通道额度（I5⑤）
            if (profile.guards.telemetryBlockEnabled) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_telemetry",
                        domainSuffix = TelemetryDomainSuffixes,
                        domainKeyword = TelemetryDomainKeywords,
                        action = SingBoxRouteRuleActionReject,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            // UDP 白名单：微信/QQ 按包名 + 通话域名直连放行（I5④，受「QQ/微信放行」开关控制）
            if (profile.guards.qqWechatUdpAllow) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_whitelist_apps",
                        network = listOf("udp"),
                        packageName = UdpWhitelistPackageNames,
                        action = SingBoxRouteRuleActionRoute,
                        outbound = directTag,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_whitelist_domains",
                        network = listOf("udp"),
                        domainSuffix = UdpWhitelistDomainSuffixes,
                        action = SingBoxRouteRuleActionRoute,
                        outbound = directTag,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_udp_ntp",
                    network = listOf("udp"),
                    port = listOf("123"),
                    action = SingBoxRouteRuleActionRoute,
                    outbound = directTag,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // 游戏 UDP 按包名直连（延迟敏感不走 CNS），插在 cns/reject 之前
            if (profile.guards.gameUdpPackages.isNotEmpty()) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_games",
                        network = listOf("udp"),
                        packageName = profile.guards.gameUdpPackages,
                        action = SingBoxRouteRuleActionRoute,
                        outbound = directTag,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            // UDP 统一走 CNS（用户定版：国外走机场节点 UDP 有问题，机场 UDP 分流规则移除）：
            // 白名单外的全部 UDP（不分国内外）走 CNS 中继，所有模式一致；纯机场模式也建 CNS 组。
            // 无 CNS 节点时按策略回落（Strict=拒绝 / AllowAll=放行）。
            // 纯机场：UDP 统一走订阅国内节点组（·国内 selector；用户定版：订阅国外节点 UDP 有问题，
            // 不再按地理分到·国外）。策略单选在纯机场页隐藏, 本规则固定生成。
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly && derivedCnTag != null) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_airport_cn",
                        network = listOf("udp"),
                        action = SingBoxRouteRuleActionRoute,
                        outbound = derivedCnTag,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            // UDP 策略：ViaCns=白名单外全部走 CNS 组 / AllowAll=直连放行 / Strict=无附加规则
            if (profile.udp == UdpPolicy.ViaCns && cnsTag != null) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_cns",
                        network = listOf("udp"),
                        action = SingBoxRouteRuleActionRoute,
                        outbound = cnsTag.orEmpty(),
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            if (profile.udp == UdpPolicy.AllowAll) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_udp_allow",
                        network = listOf("udp"),
                        action = SingBoxRouteRuleActionRoute,
                        outbound = directTag,
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
            // UDP 防跳总闸：白名单外全拒（在 allow 之后仅兜底，策略互斥由编译顺序保证）
            add(
                SingBoxRouteRuleState(
                    id = nextRuleId++,
                    remarks = "ff_udp_reject",
                    network = listOf("udp"),
                    action = SingBoxRouteRuleActionReject,
                    type = SingBoxRouteRuleTypeDefault,
                ),
            )
            // 国内外分流：机场直连/链式国内走免流组；纯机场国内走「机场·国内」组（国外落 route.final）
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportDirect ||
                profile.split.mode == features.freeflow.domain.SplitMode.Chained ||
                (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly && derivedCnTag != null)
            ) {
                add(
                    SingBoxRouteRuleState(
                        id = nextRuleId++,
                        remarks = "ff_domestic_split",
                        ruleSet = listOf(
                            managedBundledRuleSetTag(ResourceFileKind.GeositeCn),
                            managedBundledRuleSetTag(ResourceFileKind.GeoipCn),
                        ),
                        action = SingBoxRouteRuleActionRoute,
                        outbound = if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly) {
                            derivedCnTag.orEmpty()
                        } else {
                            channelTag
                        },
                        type = SingBoxRouteRuleTypeDefault,
                    ),
                )
            }
        }

        // route.final：机场直连/链式指向生效机场组，纯机场指向「机场·国外」组，纯免流指向通道组
        val activeAirport = profile.split.activeAirport
            ?.let { name -> profile.airports.firstOrNull { airport -> airport.name == name } }
            ?: profile.airports.firstOrNull()
        val routeFinal = when {
            (profile.split.mode == features.freeflow.domain.SplitMode.AirportDirect ||
                profile.split.mode == features.freeflow.domain.SplitMode.Chained) && activeAirport != null ->
                airportTagOf(activeAirport)
            profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly -> derivedFinalTag
            else -> channelTag
        }
        if (profile.split.mode != features.freeflow.domain.SplitMode.FreeOnly &&
            (activeAirport == null || routeFinal.isNullOrBlank())
        ) {
            return Result.Failed(
                InvariantId.SplitTarget,
                "当前分流模式需要机场组（先启用订阅、导入文件或内置快照）",
            )
        }

        val artifacts = Artifacts(
            groups = groups,
            outbounds = outbounds,
            routeRules = routeRules,
            dnsServers = dnsServers,
            dnsRules = dnsRules,
            routeFinal = routeFinal.orEmpty(),
            flags = setOf(FreeFlowCompiler.Flag.ForceDisableIpv6, FreeFlowCompiler.Flag.ForceRootIpv6Disabler, FreeFlowCompiler.Flag.ForceStoreFakeIp, FreeFlowCompiler.Flag.ForceLocalDns),
        )
        val violation = checkInvariants(artifacts, profile, family)
        return violation?.let { Result.Failed(it.first, it.second) } ?: Result.Success(artifacts)
    }

    /** 基础设施域收集：订阅 URL host + 机场 URL host + 所有出站 server 域（含字母）。 */
    private fun collectInfraDomains(base: AppState, profile: FreeFlowProfile): Set<String> = buildSet {
        fun addUrlHost(url: String) {
            if (url.isBlank()) return
            runCatching { URI(url).host?.lowercase() }.getOrNull()?.let(::add)
        }
        addUrlHost(profile.channel.subscriptionUrl)
        profile.airports.forEach { airport -> addUrlHost(airport.url) }
        base.outbounds.forEach { outbound ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(outbound.json).jsonObject["server"]
                    ?.jsonPrimitive?.content.orEmpty()
            }.getOrDefault("").takeIf { server -> server.isNotBlank() && server.any(Char::isLetter) }
                ?.let { add(it.lowercase()) }
        }
    }.filter(String::isNotBlank).toSet()

    // ---------------------------------------------------------------- 不变量自检

    /** 领域铁律机器化：违反返回 (不变量, 原因)，全部通过返回 null。 */
    fun checkInvariants(
        artifacts: FreeFlowCompiler.Artifacts,
        profile: FreeFlowProfile,
        family: FreeFlowFamily,
    ): Pair<InvariantId, String>? {
        val rules = artifacts.routeRules.associateBy { rule -> rule.remarks }

        // I5① QUIC 双端口（80+443，h3 备用端口穿透会被静默吞）
        rules["ff_block_quic"]?.port?.takeIf { ports -> ports.toSet() != setOf("80", "443") }
            ?.let { return InvariantId.QuicDoublePort to "ff_block_quic 必须同时封 80 与 443，实际 $it" }
            ?: run { if (rules["ff_block_quic"] == null) return InvariantId.QuicDoublePort to "ff_block_quic 缺失" }

        // I5③ IPv6 三层收口：路由拒 + AAAA 空应答 + 强制关 v6
        if (rules["ff_block_ipv6"] == null || rules["ff_block_ipv6"]?.ipVersion != 6) {
            return InvariantId.Ipv6Guard to "ff_block_ipv6 缺失"
        }
        if (artifacts.dnsRules.none { rule -> rule.remarks == "ff_aaaa_empty" }) {
            return InvariantId.Ipv6Guard to "ff_aaaa_empty 缺失"
        }
        if (FreeFlowCompiler.Flag.ForceDisableIpv6 !in artifacts.flags ||
            FreeFlowCompiler.Flag.ForceRootIpv6Disabler !in artifacts.flags
        ) {
            return InvariantId.Ipv6Guard to "IPv6 双侧收口缺失（关 v6 + ROOT 物理禁 v6 禁用器）"
        }

        // I5④ UDP 防跳总闸必须在；AllowAll 时放行规则必须在其前
        val udpRules = artifacts.routeRules.filter { rule -> "udp" in rule.network }
        val rejectIndex = udpRules.indexOfFirst { rule -> rule.remarks == "ff_udp_reject" }
        if (rejectIndex < 0) return InvariantId.UdpReject to "ff_udp_reject 缺失"
        if (profile.udp == UdpPolicy.AllowAll) {
            val allowIndex = udpRules.indexOfFirst { rule -> rule.remarks == "ff_udp_allow" }
            if (allowIndex < 0 || allowIndex > rejectIndex) {
                return InvariantId.UdpReject to "AllowAll 需要 ff_udp_allow 排在 ff_udp_reject 之前"
            }
        }
        // 纯机场：UDP 统一走订阅国内节点组（ff_udp_airport_cn，用户定版：国外节点 UDP 有问题）
        if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly &&
            artifacts.routeRules.none { rule -> rule.remarks == "ff_udp_airport_cn" }
        ) {
            return InvariantId.UdpReject to "纯机场模式缺少 ff_udp_airport_cn 规则（UDP 走订阅国内节点）"
        }
        if (profile.udp == UdpPolicy.ViaCns &&
            profile.split.mode != features.freeflow.domain.SplitMode.AirportOnly &&
            udpRules.none { rule -> rule.remarks == "ff_udp_cns" }
        ) {
            return InvariantId.UdpReject to "UDP 策略为「走 CNS 隧道」但未生成 ff_udp_cns 规则（缺 CNS 节点）"
        }

        // I5⑤ 遥测拦截开关一致性
        if (profile.guards.telemetryBlockEnabled && rules["ff_telemetry"] == null) {
            return InvariantId.Telemetry to "telemetryBlockEnabled 但 ff_telemetry 缺失"
        }

        // I5⑦ 私网直连必须用显式 ip_cidr（ip_is_private 的 fc00::/7 会误吞 FakeIP v6 池）
        if (artifacts.routeRules.any { rule -> rule.ipIsPrivate }) {
            return InvariantId.ExplicitPrivateCidr to "禁止 ip_is_private（会误吞 fc00::/18 FakeIP 池），用显式 ip_cidr"
        }

        // I5⑧ NTP 专项必须在 FakeIP 入口之前（否则 A/AAAA 被假 IP 截走，对时死循环）
        val entryIndex = artifacts.dnsRules.indexOfFirst { rule -> rule.remarks == "ff_fakeip_entry" }
        val ntpIndex = artifacts.dnsRules.indexOfFirst { rule -> rule.remarks == "ff_ntp" }
        if (entryIndex < 0 || ntpIndex < 0 || ntpIndex >= entryIndex) {
            return InvariantId.NtpBeforeFakeIpEntry to "ff_ntp 必须存在且排在 ff_fakeip_entry 之前"
        }

        // 农行兼容：配了 CNS 必须有 TCP 中继规则；没配 CNS 时规则缺席属预期（软依赖）
        if (profile.guards.agriBankCompatEnabled &&
            profile.split.mode != features.freeflow.domain.SplitMode.AirportOnly &&
            profile.cnsNodes.isNotEmpty() &&
            rules["ff_agribank_cns"] == null
        ) {
            return InvariantId.AgriBankCompat to "ff_agribank_cns 缺失"
        }

        // I4 FakeIP 服务器必须在产物里
        if (artifacts.dnsServers.none { server -> server.type == "fakeip" }) {
            return InvariantId.FakeIpServer to "FakeIP 服务器缺失"
        }

        // I12 CNS 独立组隔离 + 隧道路径不得携带 udp_flag 串（服务端按 contains 分流，带上 TCP 全丢）
        val channelGroupId = artifacts.groups
            .firstOrNull { group -> group.name == FreeFlowCompiler.FreeFlowChannelGroupName }?.id
        val cnsMembers = artifacts.outbounds.filter { outbound -> outbound.type == MtlCnsOutboundType }
        if (channelGroupId != null && cnsMembers.any { outbound -> outbound.groupId == channelGroupId }) {
            return InvariantId.CnsIsolation to "CNS 出站必须放独立组（组 selector 互指会 circular dependency FATAL）"
        }
        if (cnsMembers.any { outbound -> outbound.json.contains("httpUDP") }) {
            return InvariantId.CnsNoUdpFlag to "CNS 配置不得携带 udp_flag 串（会被服务端当 UDP 会话，TCP 数据全丢）"
        }

        // 分流目标一致性：机场直连/纯机场必须有国内分流规则，且 final 不得回落到通道组
        if (profile.split.mode != features.freeflow.domain.SplitMode.FreeOnly) {
            if (rules["ff_domestic_split"] == null) {
                return InvariantId.SplitTarget to "机场分流模式必须有 ff_domestic_split 国内分流规则"
            }
            if (channelGroupId != null &&
                artifacts.routeFinal == managedOutboundGroupSelectorTag(channelGroupId, FreeFlowCompiler.FreeFlowChannelGroupName)
            ) {
                return InvariantId.SplitTarget to "机场分流模式的 route.final 不得指向免流通道组"
            }
        }
        return null
    }

}

    // ---------------------------------------------------------------- AppState 区间管理

    /** 免流产物是否在场（ownerKey 为主，旧版字符串标记兜底）。 */
    fun AppState.freeFlowApplied(): Boolean =
        outboundGroups.any { group -> group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey } ||
            outboundGroups.any { group -> group.name == FreeFlowCompiler.LegacyChannelGroupName } ||
            routeRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) } ||
            routeRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.LegacyRuleRemarksPrefix) }

    internal fun ownedGroupNames(): Set<String> = setOf(
        FreeFlowCompiler.FreeFlowChannelGroupName,
        FreeFlowCompiler.FreeFlowCnsGroupName,
    )

    /**
     * 摘除全部免流产物（含旧版 features/mtl 遗留）：owned 组+成员、ff_/mtl_ 规则、
     * fakeip 服务器、组 selector 引用清理。机场组也一并摘除——保留语义由调用方
     * （UseCase）在摘除前把要保留的机场组+成员摘出来、编译后放回。
     */
    fun AppState.withoutFreeFlowArtifacts(): AppState {
        val removedGroupIds = outboundGroups
            .filter { group ->
                group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey ||
                    group.name in ownedGroupNames() ||
                    group.name == FreeFlowCompiler.LegacyChannelGroupName ||
                    group.userAgent == features.freeflow.domain.FreeFlowSubscriptionUserAgent
            }
            .mapTo(mutableSetOf()) { group -> group.id }
        var state = this
        if (removedGroupIds.isNotEmpty()) {
            val removedTags = buildSet {
                outboundGroups
                    .filter { group -> group.id in removedGroupIds }
                    .forEach { group -> add(managedOutboundGroupSelectorTag(group.id, group.name)) }
                outbounds
                    .filter { outbound -> outbound.groupId in removedGroupIds }
                    .forEach { outbound -> add(managedOutboundTag(outbound.id, outbound.remarks)) }
            }
            state = state.copy(
                outboundGroups = outboundGroups.filterNot { group -> group.id in removedGroupIds },
                outbounds = outbounds.filterNot { outbound -> outbound.groupId in removedGroupIds },
            ).withRemovedManagedOutboundTags(removedTags)
        }
        val removedDnsServerIds = dnsServers
            .filter { server ->
                server.type == "fakeip" &&
                    (server.remarks == FreeFlowCompiler.FreeFlowFakeIpRemarks || server.remarks == "fakeip")
            }
            .mapTo(mutableSetOf()) { server -> server.id }
        if (removedDnsServerIds.isNotEmpty()) {
            state = state.copy(dnsServers = state.dnsServers.filterNot { server -> server.id in removedDnsServerIds })
        }
        if (state.dnsRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) }) {
            state = state.copy(
                dnsRules = state.dnsRules.filterNot { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) },
            )
        }
        // 路由规则半边：摘除 ff_/mtl_ 前缀规则（apply 路径会全量替换，此处保 strip 语义完整）
        if (state.routeRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) }) {
            state = state.copy(
                routeRules = state.routeRules.filterNot { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) },
            )
        }
        if (state.routeRules.any { rule -> rule.remarks.startsWith(FreeFlowCompiler.LegacyRuleRemarksPrefix) }) {
            state = state.copy(
                routeRules = state.routeRules.filterNot { rule -> rule.remarks.startsWith(FreeFlowCompiler.LegacyRuleRemarksPrefix) },
            )
        }
        if (state != this) state = state.withPrunedDnsServerReferences()
        return state
    }

    /** 摘除 ff_/mtl_ 路由规则（AppState 区间替换的路由半边）。 */
    fun AppState.withoutFreeFlowRouteRules(): Pair<AppState, Boolean> {
        if (routeRules.none { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) }) {
            return this to false
        }
        return copy(
            routeRules = routeRules.filterNot { rule -> rule.remarks.startsWith(FreeFlowCompiler.FreeFlowRuleRemarksPrefix) },
        ) to true
    }

    /** 编译产物落进 AppState（调用方保证已 strip）：免流组排第一，flags 强制落位。 */
    fun AppState.withFreeFlowArtifactsApplied(artifacts: FreeFlowCompiler.Artifacts): AppState {
        val nextOutboundId = artifacts.outbounds.maxOfOrNull(OutboundState::id)?.plus(1)
            ?: nextOutboundId
        val nextGroupId = artifacts.groups.maxOfOrNull(OutboundGroupState::id)?.plus(1)
            ?: nextOutboundGroupId
        return copy(
            // 免流组固定排第一：代理页分组标签默认选中第一个分组（旧版真机反馈的显示问题）
            outboundGroups = artifacts.groups + outboundGroups,
            outbounds = artifacts.outbounds + outbounds,
            nextOutboundGroupId = maxOf(nextOutboundGroupId, nextGroupId),
            nextOutboundId = maxOf(nextOutboundId, this.nextOutboundId),
            routeRules = artifacts.routeRules,
            nextRouteRuleId = (artifacts.routeRules.maxOfOrNull(SingBoxRouteRuleState::id) ?: 0) + 1,
            dnsServers = artifacts.dnsServers,
            nextDnsServerId = (artifacts.dnsServers.maxOfOrNull(SingBoxDnsServerState::id) ?: 0) + 1,
            dnsRules = artifacts.dnsRules,
            nextDnsRuleId = (artifacts.dnsRules.maxOfOrNull(SingBoxDnsRuleState::id) ?: 0) + 1,
            routeFinal = artifacts.routeFinal,
            enableIpv6 = if (FreeFlowCompiler.Flag.ForceDisableIpv6 in artifacts.flags) false else enableIpv6,
            enableRootIpv6Disabler = if (FreeFlowCompiler.Flag.ForceRootIpv6Disabler in artifacts.flags) true else enableRootIpv6Disabler,
            storeFakeIp = if (FreeFlowCompiler.Flag.ForceStoreFakeIp in artifacts.flags) true else storeFakeIp,
            enableLocalDns = if (FreeFlowCompiler.Flag.ForceLocalDns in artifacts.flags) true else enableLocalDns,
        )
    }

    /** owned 机场组（编译透传保留的组），按 profile 里的名字过滤由 UseCase 负责。 */
    fun AppState.ownedAirportGroups(): List<OutboundGroupState> =
        outboundGroups.filter { group -> group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey }
            .filter { group -> group.name != FreeFlowCompiler.FreeFlowChannelGroupName && group.name != FreeFlowCompiler.FreeFlowCnsGroupName }

    /** owned 机场组及其成员快照（strip 前摘出、编译后放回）。 */
    data class AirportSnapshot(
        val groups: List<OutboundGroupState>,
        val outbounds: List<OutboundState>,
    )

    fun AppState.takeAirportSnapshot(names: Set<String>): AirportSnapshot {
        val groupIds = outboundGroups
            .filter { group -> group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey && group.name in names }
            .mapTo(mutableSetOf()) { group -> group.id }
        return AirportSnapshot(
            groups = outboundGroups.filter { group -> group.id in groupIds },
            outbounds = outbounds.filter { outbound -> outbound.groupId in groupIds },
        )
    }

    fun AppState.restoreAirportSnapshot(snapshot: AirportSnapshot): AppState = copy(
        outboundGroups = outboundGroups + snapshot.groups,
        outbounds = outbounds + snapshot.outbounds,
    )

    // -------------------------------------------------------------- 百度链式 detour 注入

    /**
     * 机场组成员摘除 detour：机场成员本不需要前置出站，历史导入/成员复制可能残留指向
     * 已不存在组的选择器 tag（组被剪后依赖悬空=启动 FATAL）。链式模式的通道 detour 随后另行注入。
     */
    fun AppState.withoutAirportMemberDetours(): AppState {
        val airportGroupIds = outboundGroups
            .filter { group ->
                group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey &&
                    group.name != FreeFlowCompiler.FreeFlowChannelGroupName &&
                    group.name != FreeFlowCompiler.FreeFlowCnsGroupName
            }
            .mapTo(mutableSetOf()) { group -> group.id }
        if (airportGroupIds.isEmpty()) return this
        var changed = false
        val updated = outbounds.map { outbound ->
            if (outbound.groupId !in airportGroupIds) return@map outbound
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(outbound.json)
                    as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: return@map outbound
            if (!parsed.containsKey("detour")) return@map outbound
            changed = true
            outbound.copy(
                json = kotlinx.serialization.json.JsonObject(
                    buildMap {
                        parsed.forEach { (key, value) -> if (key != "detour") put(key, value) }
                    },
                ).toString(),
            )
        }
        if (!changed) return this
        return copy(outbounds = updated)
    }

    /** 链式 detour 写入出站 JSON（覆盖既有 detour；无需改动返回 null，解析失败原样跳过）。 */
    fun patchOutboundChainDetourJson(json: String, detourTag: String): String? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json)
            as? kotlinx.serialization.json.JsonObject ?: return null
        if ((root["detour"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == detourTag) return null
        kotlinx.serialization.json.JsonObject(
            buildMap {
                root.forEach { (key, value) -> if (key != "detour") put(key, value) }
                put("detour", kotlinx.serialization.json.JsonPrimitive(detourTag))
            },
        ).toString()
    }.getOrNull()

    /** 百度链式：生效机场组成员全部 detour 骑免流通道组（仅 Chained 模式调用；TCP 链，UDP 无法骑链）。 */
    fun AppState.withAirportChainDetour(groupName: String, detourTag: String): AppState {
        val group = outboundGroups.firstOrNull { item ->
            item.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey && item.name == groupName
        } ?: return this
        val updated = outbounds.map { outbound ->
            if (outbound.groupId != group.id) return@map outbound
            val nextJson = patchOutboundChainDetourJson(outbound.json, detourTag) ?: return@map outbound
            if (nextJson != outbound.json) outbound.copy(json = nextJson) else outbound
        }
        if (updated == outbounds) return this
        return copy(outbounds = updated)
    }

    // -------------------------------------------------------------- 通道外来货透传

    /**
     * 通道组外来货快照：编辑器手添/订阅拉取进「免流网关」组的成员（无 ff 标记）。
     * 编译整组重建时不冲掉它们（UseCase 在套用后回填换组），换族同样保留。
     */
    fun AppState.takeChannelCargoSnapshot(): List<OutboundState> {
        val channelGroup = outboundGroups.firstOrNull { group ->
            group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey &&
                group.name == FreeFlowCompiler.FreeFlowChannelGroupName
        } ?: return emptyList()
        return outbounds.filter { outbound ->
            outbound.groupId == channelGroup.id &&
                (outbound.meta == null || !outbound.meta.contains("\"ff\""))
        }
    }

    /**
     * 通道外来货回填：换新 id 并重写内嵌 tag 后并入通道组（旧 id 已被新编译成员复用，
     * 原样回填会产生重复主键/重复 tag——换族闪退事故根因），同名去重。
     */
    fun AppState.restoreChannelCargo(
        cargo: List<OutboundState>,
        forcedDetourTag: String? = null,
    ): AppState {
        if (cargo.isEmpty()) return this
        val channelGroup = outboundGroups.firstOrNull { group ->
            group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey &&
                group.name == FreeFlowCompiler.FreeFlowChannelGroupName
        } ?: return this
        val existingRemarks = outbounds
            .filter { outbound -> outbound.groupId == channelGroup.id }
            .mapTo(mutableSetOf()) { outbound -> outbound.remarks }
        val validTags = outbounds.mapTo(mutableSetOf(), ::outboundEmbeddedOrManagedTag)
        var nextId = nextOutboundId.coerceAtLeast((outbounds.maxOfOrNull(OutboundState::id) ?: 0) + 1)
        val restored = buildList {
            cargo.forEach { member ->
                if (member.remarks in existingRemarks) return@forEach
                // 同名去重要含本批已收的（两个同名外来货只留头一个）
                existingRemarks += member.remarks
                val id = nextId
                nextId += 1
                val tag = managedOutboundTag(id, member.remarks)
                add(
                    member.copy(
                        id = id,
                        groupId = channelGroup.id,
                        json = rewriteCargoReferences(member.json, tag, forcedDetourTag, validTags),
                    ),
                )
            }
        }
        if (restored.isEmpty()) return this
        // 就近插到通道组成员段末尾（保持组段连续，编辑器按组段定位插入；表尾追加会破坏段序）
        val insertAt = outbounds.indexOfLast { outbound -> outbound.groupId == channelGroup.id } + 1
        if (insertAt == 0) return this
        return copy(
            outbounds = outbounds.subList(0, insertAt) + restored + outbounds.subList(insertAt, outbounds.size),
            nextOutboundId = nextId,
        )
    }

    /**
     * 出站引用重写：tag 换成新 id 形态；detour 悬空引用（订阅导入写死的对端 managed tag，
     * 换族后对端换新 id 旧 tag 已不存在）摘除直连兜底——原样保留会让校验/内核永远报错，
     * 换族死锁（审计 high #1）；彩信族强制 detour 骑 WAP 中转（外来货直连=全量计费）。
     */
    private fun rewriteCargoReferences(
        json: String,
        tag: String,
        forcedDetourTag: String?,
        validTags: Set<String>,
    ): String = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
            as? kotlinx.serialization.json.JsonObject ?: return json
        val currentDetour = (obj["detour"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val nextDetour = when {
            forcedDetourTag != null -> forcedDetourTag
            currentDetour != null && currentDetour in validTags -> currentDetour
            else -> null
        }
        kotlinx.serialization.json.JsonObject(
            buildMap {
                obj.forEach { (key, value) -> if (key != "tag" && key != "detour") put(key, value) }
                put("tag", kotlinx.serialization.json.JsonPrimitive(tag))
                if (nextDetour != null) put("detour", kotlinx.serialization.json.JsonPrimitive(nextDetour))
            },
        ).toString()
    }.getOrDefault(json)

    /** 出站生效 tag = json 内嵌 tag（编辑器货）或 id+备注推导（编译器货）。 */
    private fun outboundEmbeddedOrManagedTag(outbound: OutboundState): String = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(outbound.json)
            as? kotlinx.serialization.json.JsonObject)?.get("tag")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.contentOrNull
    }.getOrNull() ?: managedOutboundTag(outbound.id, outbound.remarks)

    // -------------------------------------------------------------- 手机卡运营商排序

    /** 手机卡运营商（SIM MCC+MNC 推导）。 */
    enum class FfSimCarrier { Unicom, Telecom, Mobile, Unknown }

    /** simOperator（如 46001）→ 运营商；中国 MNC: 联通 01/06/09，电信 03/05/11，移动 00/02/04/07/08。 */
    fun ffSimCarrierFromOperator(operator: String): FfSimCarrier {
        val normalized = operator.trim().take(5)
        if (normalized.length < 5) return FfSimCarrier.Unknown
        return when (normalized.substring(3)) {
            "01", "06", "09" -> FfSimCarrier.Unicom
            "03", "05", "11" -> FfSimCarrier.Telecom
            "00", "02", "04", "07", "08" -> FfSimCarrier.Mobile
            else -> FfSimCarrier.Unknown
        }
    }

    /** 节点运营商识别（备注含关键字），按卡序给名次；认不出 = 永远殿后。 */
    internal fun carrierNodeRank(remarks: String, carrier: FfSimCarrier): Int {
        val keywords = when (carrier) {
            FfSimCarrier.Unicom, FfSimCarrier.Unknown -> listOf("联通", "电信", "移动")
            FfSimCarrier.Telecom -> listOf("电信", "联通", "移动")
            FfSimCarrier.Mobile -> listOf("移动", "联通", "电信")
        }
        val hit = keywords.indexOfFirst { keyword -> keyword in remarks }
        return if (hit < 0) keywords.size else hit
    }

    /**
     * 通道成员按卡序稳定重排（同名次保原序）：默认/联通卡 联通→电信→移动，电信卡 电信→联通→移动，
     * 移动卡 移动→联通→电信；其他节点永远最后。仅百度直连/裸 CONNECT 两族（彩信族不动）。
     */
    fun AppState.withChannelNodesCarrierOrdered(familyKey: String, carrier: FfSimCarrier): AppState {
        if (familyKey != features.freeflow.domain.BaiduT5Family.key &&
            familyKey != features.freeflow.domain.TpboxBareFamily.key
        ) {
            return this
        }
        val channelGroup = outboundGroups.firstOrNull { group ->
            group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey &&
                group.name == FreeFlowCompiler.FreeFlowChannelGroupName
        } ?: return this
        val members = outbounds.filter { outbound -> outbound.groupId == channelGroup.id }
        val sorted = members.sortedBy { outbound -> carrierNodeRank(outbound.remarks, carrier) }
        if (sorted == members) return this
        // 原位替换成员段（保持组段连续；尾部追加会让按组段定位的插入把新成员插到队首）
        val segmentStart = outbounds.indexOfFirst { outbound -> outbound.id == members.first().id }
        if (segmentStart < 0) return this
        return copy(
            outbounds = outbounds.subList(0, segmentStart) +
                sorted +
                outbounds.subList(segmentStart + members.size, outbounds.size),
        )
    }


// -------------------------------------------------------------- 免流存续判定

/**
 * 免流存续 = 编译产物组（ownerKey=freeflow）在档。共享编译层（全局选择器剔 direct、
 * 最终路由 fail-closed）与首页模式锁共用此判定；与 withFreeFlowArtifactsApplied 同生死。
 */
fun AppState.isFreeFlowChannelApplied(): Boolean =
    outboundGroups.any { group -> group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey }

/** UI 侧近似：免流存续且最终路由将失效（组被禁用/final 空）——与 compileRoute 的 fail-closed 同判定。 */
fun AppState.freeFlowFailClosed(): Boolean =
    isFreeFlowChannelApplied() && (
        routeFinal.isBlank() ||
            outboundGroups.any { group ->
                group.ownerKey == FreeFlowCompiler.FreeFlowOwnerKey && !group.enabled
            }
        )
