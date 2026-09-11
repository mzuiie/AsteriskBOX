// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.usecase

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.OutboundGroupUpdateStatus
import app.SingBoxRouteRuleState
import app.managedOutboundGroupSelectorTag
import app.managedOutboundTag
import app.withRemovedManagedOutboundTags
import app.nextAvailableOutboundGroupId
import engine.singbox.DefaultSingBoxRouteRules
import engine.singbox.config.parseSingBoxJson
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.compile.freeFlowApplied
import features.freeflow.compile.takeAirportSnapshot
import features.freeflow.compile.restoreChannelCargo
import features.freeflow.compile.takeChannelCargoSnapshot
import features.freeflow.compile.withAirportChainDetour
import features.freeflow.compile.withoutAirportMemberDetours
import features.freeflow.compile.withChannelNodesCarrierOrdered
import features.freeflow.compile.ffSimCarrierFromOperator
import features.freeflow.compile.withFreeFlowArtifactsApplied
import features.freeflow.compile.restoreAirportSnapshot
import features.freeflow.compile.withoutFreeFlowArtifacts
import features.freeflow.compile.FreeFlowCompiler.FreeFlowOwnerKey
import features.freeflow.domain.AirportSpec
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.FreeFlowSubscriptionUserAgent
import features.freeflow.domain.GatewayProfile
import features.freeflow.domain.GatewayVerdict
import features.freeflow.domain.HeaderSpec
import features.freeflow.domain.NodeSource
import features.freeflow.domain.UdpPolicy
import features.freeflow.domain.suggestions
import features.freeflow.migrate.FreeFlowMigrator
import features.freeflow.store.FreeFlowObfHostStore
import features.freeflow.store.FreeFlowProbeArchiveStore
import features.freeflow.store.FreeFlowProfileStore
import features.freeflow.store.GatewayRejectStatsStore
import kotlinx.coroutines.CancellationException

/**
 * 免流用例：全部操作 = 读档 → 改档 → 编译（含铁律自检）→ validate → CAS 提交 →
 * （运行中由 commit lambda 热重载引擎，见 App.kt I10）。声明档是唯一事实源，
 * AppState 里的免流产物是编译缓存； airport 组成员是透传货（订阅拉取/导入不丢）。
 */
class FreeFlowUseCase(
    private val snapshot: () -> AppState,
    private val commit: suspend (AppState, AppState) -> kotlin.Result<Boolean>,
    private val validate: suspend (AppState) -> Unit,
    private val profileStore: FreeFlowProfileStore,
    private val obfHostStore: FreeFlowObfHostStore,
    private val probeArchive: FreeFlowProbeArchiveStore,
    private val rejectStats: GatewayRejectStatsStore,
    private val prober: suspend (server: String, port: Int, handshake: features.freeflow.domain.HandshakeSpec) -> GatewayProfile,
    /** SIM 运营商原始串（TelephonyManager.simOperator，如 46001）；取不到 = 空串 = 默认排序。 */
    private val carrierOperator: () -> String = { "" },
    /** 机场组订阅拉取（主动获取节点）：按组 id 拉取并落库，抛错 = 失败。App 装配时接订阅更新器。 */
    private val fetchGroupNodes: suspend (groupId: Int) -> AirportNodesFetchSummary = {
        throw IllegalStateException("订阅拉取未接入")
    },
) {

    /** 免流操作结果。 */
    sealed interface Result {
        data class Applied(val selectorTag: String, val nodeCount: Int) : Result
        data object AlreadyApplied : Result
        data class Failed(val error: Throwable) : Result
    }

    /** 机场节点拉取汇总（订阅层适配注入，导入数 0 且无变化 = 订阅无更新）。 */
    data class AirportNodesFetchSummary(
        val importedCount: Int,
        val allUnchanged: Boolean,
    )

    // ------------------------------------------------------------- 档读取 / 迁移

    /** 当前声明档（无档 = 默认停用档）。 */
    suspend fun currentProfile(): FreeFlowProfile = profileStore.load() ?: FreeFlowProfile(enabled = false)

    /**
     * 启动迁移：档不存在且旧版产物在场 → 逆向推导档、保存并按档重建产物。
     * 返回是否执行了迁移。旧产物无法识别时落一份默认停用档，保证幂等。
     */
    suspend fun migrateIfNeeded(): Boolean {
        if (profileStore.load() != null) return false
        val state = snapshot()
        val profile = if (FreeFlowMigrator.legacyArtifactsDetected(state) || FreeFlowCompiler.run { state.freeFlowApplied() }) {
            FreeFlowMigrator.deriveProfile(state) ?: FreeFlowProfile(enabled = false)
        } else {
            FreeFlowProfile(enabled = false)
        }
        profileStore.save(profile)
        if (!profile.enabled) return false
        return when (val committed = saveAndCommitInternal(profile)) {
            is Result.Applied, is Result.AlreadyApplied -> true
            is Result.Failed -> false
        }
    }

    // ------------------------------------------------------------- 通道

    /** 一键套用（幂等）：确保档启用并按档全量重建产物。 */
    suspend fun applyTemplate(familyKey: String? = null): Result {
        val profile = currentProfile()
        val channel = profile.channel.copy(familyKey = familyKey ?: profile.channel.familyKey)
        return saveAndCommit(profile.copy(enabled = true, channel = channel))
    }

    /**
     * 切换通道族：族内置货由编译器随族整组重建；档内手添/订阅成员原样保留
     * （旧实现 nodes 清空会连自定义节点一起删掉，真机反馈已修）。
     */
    suspend fun setChannelFamily(familyKey: String): Result {
        val profile = currentProfile()
        return saveAndCommit(
            profile.copy(
                enabled = true,
                channel = profile.channel.copy(familyKey = familyKey),
            ),
        )
    }

    /** 网关订阅地址（令牌轮换走订阅，I8）；留空 = 清除。 */
    suspend fun setChannelSubscription(rawUrl: String): Result {
        val normalized = rawUrl.trim()
        if (normalized.isNotEmpty() && !normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return Result.Failed(IllegalArgumentException("Invalid subscription URL"))
        }
        val profile = currentProfile()
        return saveAndCommit(
            profile.copy(
                enabled = true,
                channel = profile.channel.copy(subscriptionUrl = normalized),
            ),
        )
    }

    // ------------------------------------------------------------- 策略

    suspend fun setUdpPolicy(policy: UdpPolicy): Result {
        // ViaCns 前置条件：至少一条 CNS 节点（否则编译期才拦截，用户看到的是内部不变量编号）
        if (policy == UdpPolicy.ViaCns &&
            currentProfile().cnsNodes.isEmpty() &&
            currentProfile().split.mode != features.freeflow.domain.SplitMode.AirportOnly
        ) {
            return Result.Failed(IllegalStateException("还没有 CNS 节点：请先在下方「CNS 隧道」添加节点"))
        }
        val profile = currentProfile()
        return saveAndCommit(profile.copy(enabled = true, udp = policy))
    }

    suspend fun setGameUdp(packageNames: List<String>): Result = mutateGuards { guards ->
        guards.copy(
            gameUdpPackages = packageNames.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
    }

    suspend fun setTelemetryEnabled(enabled: Boolean): Result = mutateGuards { guards ->
        guards.copy(telemetryBlockEnabled = enabled)
    }

    /** 农行兼容实验开关（默认关）：农行 TCP 走 CNS 中继，软依赖 CNS 节点。 */
    suspend fun setAgriBankCompat(enabled: Boolean): Result = mutateGuards { guards ->
        guards.copy(agriBankCompatEnabled = enabled)
    }

    /** QQ/微信放行开关（默认开=现状）：关=通话 UDP 由 UDP 策略决定去向。 */
    suspend fun setQqWechatUdpAllow(enabled: Boolean): Result = mutateGuards { guards ->
        guards.copy(qqWechatUdpAllow = enabled)
    }

    private suspend fun mutateGuards(transform: (features.freeflow.domain.GuardSpec) -> features.freeflow.domain.GuardSpec): Result {
        val profile = currentProfile()
        return saveAndCommit(profile.copy(enabled = true, guards = transform(profile.guards)))
    }

    // ------------------------------------------------------------- CNS

    suspend fun addOrUpdateCns(
        name: String,
        server: String,
        port: String,
        password: String,
        masking: Boolean,
        maskHost: String = "",
        prependFree: Boolean = true,
        proxyKey: String = "Meng",
        udpFlag: String = "httpUDP",
    ): Result {
        val normalizedServer = server.trim()
        val normalizedPort = port.trim().toIntOrNull()
            ?.takeIf { value -> value in 1..65535 }
            ?: return Result.Failed(IllegalArgumentException("CNS port is invalid"))
        if (normalizedServer.isBlank() || password.isBlank()) {
            return Result.Failed(IllegalArgumentException("CNS server and password are required"))
        }
        val remarks = name.trim()
        val spec = CnsSpec(
            name = remarks,
            server = normalizedServer,
            port = normalizedPort,
            password = password,
            masking = masking,
            maskHost = maskHost.trim(),
            prependFree = prependFree,
            proxyKey = proxyKey.trim().ifBlank { "Meng" },
            udpFlag = udpFlag.trim().ifBlank { "httpUDP" },
        )
        val profile = currentProfile()
        val nextNodes = profile.cnsNodes
            .filterNot { node -> node.name == spec.name }
            .plus(spec)
        return saveAndCommit(
            profile.copy(enabled = true, cnsNodes = nextNodes),
        )
    }

    suspend fun removeCns(name: String): Result {
        val remarks = name.trim()
        val profile = currentProfile()
        val nextNodes = profile.cnsNodes.filterNot { node -> node.name == remarks }
        if (nextNodes.size == profile.cnsNodes.size) return Result.AlreadyApplied
        return saveAndCommit(profile.copy(cnsNodes = nextNodes))
    }

    // ------------------------------------------------------------- 机场 / 分流

    /** 启用/更新机场订阅（同名更新、异名新增）；成员由订阅拉取/导入进组（编译透传保留）。 */
    suspend fun addOrUpdateAirport(name: String, rawUrl: String): Result {
        val normalized = rawUrl.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return Result.Failed(IllegalArgumentException("Invalid subscription URL"))
        }
        val airportName = name.trim().ifBlank { FreeFlowCompiler.FreeFlowAirportGroupName }
        val profile = currentProfile()
        val airports = profile.airports
            .filterNot { airport -> airport.name == airportName }
            .plus(AirportSpec(name = airportName, url = normalized))
        return saveAndCommit(
            profile.copy(enabled = true, airports = airports),
        )
    }

    suspend fun removeAirport(name: String): Result {
        val profile = currentProfile()
        val airports = profile.airports.filterNot { airport -> airport.name == name }
        if (airports.size == profile.airports.size) return Result.AlreadyApplied
        val split = profile.split.takeIf {
            it.mode == features.freeflow.domain.SplitMode.FreeOnly || it.activeAirport != name
        } ?: features.freeflow.domain.SplitSpec()
        return saveAndCommit(profile.copy(airports = airports, split = split))
    }

    /** 分流三模式：纯免流 / 机场直连（国内免流国外走机场）/ 纯机场（国内外分组全走机场）。 */
    suspend fun setSplitMode(mode: features.freeflow.domain.SplitMode, activeName: String? = null): Result {
        val profile = currentProfile()
        if (mode != features.freeflow.domain.SplitMode.FreeOnly && profile.airports.isEmpty()) {
            return Result.Failed(IllegalStateException("还没有机场组：请先启用订阅或导入节点"))
        }
        val split = features.freeflow.domain.SplitSpec(
            mode = mode,
            activeAirport = activeName ?: profile.split.activeAirport ?: profile.airports.firstOrNull()?.name,
        )
        return saveAndCommit(profile.copy(split = split))
    }

    /** 机场混淆 Host（按组存 prefs）：提交时注入组内成员，随编译重注入。 */
    suspend fun setAirportObfHost(name: String, host: String): Result {
        obfHostStore.set(name, host.trim())
        return saveAndCommit(currentProfile())
    }

    fun airportObfHost(name: String): String = obfHostStore.get(name)

    /**
     * 主动获取机场节点：对该机场的节点组逐组走订阅拉取（与代理页手动更新同一条管线，
     * CAS 落库），纯机场模式下活动机场的 ·国内/·国外 派生组一并刷新。
     * 只拉取不重编译——运行中的隧道要在「套用」后才会用上新节点（与代理页手动更新同语义）。
     */
    suspend fun fetchAirportNodes(name: String): AirportNodesFetchSummary {
        val airportName = name.trim()
        val profile = currentProfile()
        val groupNames = buildList {
            add(airportName)
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly &&
                (profile.split.activeAirport ?: profile.airports.firstOrNull()?.name) == airportName
            ) {
                val (cnName, finalName) = FreeFlowCompiler.derivedAirportGroupNames(airportName)
                add(cnName)
                add(finalName)
            }
        }
        val groupIds = snapshot().outboundGroups
            .filter { group ->
                group.ownerKey == FreeFlowOwnerKey && group.name in groupNames && group.url.isNotBlank()
            }
            .map { group -> group.id }
        if (groupIds.isEmpty()) {
            throw IllegalStateException("机场组不存在或没有订阅链接，请先套用免流配置")
        }
        var importedCount = 0
        var changed = false
        for (groupId in groupIds) {
            val summary = fetchGroupNodes(groupId)
            importedCount += summary.importedCount
            changed = changed || !summary.allUnchanged
        }
        return AirportNodesFetchSummary(importedCount = importedCount, allUnchanged = !changed)
    }

    // ------------------------------------------------------------- 诊断

    fun probeArchiveFor(server: String): GatewayProfile? = probeArchive.get(server)

    fun probeArchiveAll(): List<GatewayProfile> = probeArchive.all()

    fun rejectStatsTop(): Map<String, Int> = rejectStats.top()

    fun recordGatewayReject(suffix: String) {
        rejectStats.record(suffix)
    }

    /**
     * 网关能力探测（5 组最小 CONNECT，绑物理网卡）：结论只进档案与建议，
     * 不自动改配置（守住审计 #5 结论）。
     */
    suspend fun probeGateway(): GatewayProfile? {
        val profile = currentProfile()
        val endpoint = channelEndpoint(profile) ?: return null
        val result = prober(endpoint.first, endpoint.second, endpoint.third)
        probeArchive.put(result)
        return result
    }

    fun profileSuggestions(profile: GatewayProfile): List<features.freeflow.domain.FreeFlowSuggestion> =
        profile.suggestions()

    // ------------------------------------------------------------- 移除 / 导出

    /** 移除免流：摘除全部产物（路由恢复默认），恢复被强制的开关原值，档保留置停用。 */
    suspend fun removeFreeFlow(): Result {
        val expected = snapshot()
        val stripped = expected.withoutFreeFlowArtifacts().copy(
            routeRules = DefaultSingBoxRouteRules,
            nextRouteRuleId = (DefaultSingBoxRouteRules.maxOfOrNull(SingBoxRouteRuleState::id) ?: 0) + 1,
            routeFinal = "",
            enableRootIpv6Disabler = currentProfile().restore.enableRootIpv6Disabler,
        )
        profileStore.save(currentProfile().copy(enabled = false))
        return try {
            validate(stripped)
            commit(expected, stripped).fold(
                onSuccess = { committed ->
                    if (committed) Result.Applied("", 0)
                    else Result.Failed(IllegalStateException("State changed during remove"))
                },
                onFailure = Result::Failed,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.Failed(error)
        }
    }

    /** 档导出（JSON 文本，随粘贴/文件分享）。 */
    suspend fun exportProfileJson(): String? {
        val profile = profileStore.load() ?: return null
        return runCatching {
            features.freeflow.store.FreeFlowJson.instance.encodeToString(
                FreeFlowProfile.serializer(),
                profile,
            )
        }.getOrNull()
    }

    /** 档导入（JSON 文本）：解析通过即保存并按档重建。 */
    suspend fun importProfileJson(raw: String): Result {
        val profile = runCatching {
            features.freeflow.store.FreeFlowJson.instance.decodeFromString<FreeFlowProfile>(raw.trim())
        }.getOrNull() ?: return Result.Failed(IllegalArgumentException("无效的免流档 JSON"))
        return saveAndCommit(profile)
    }

    // ------------------------------------------------------------- 内部：保存+提交闭环

    private fun channelEndpoint(
        profile: FreeFlowProfile,
    ): Triple<String, Int, features.freeflow.domain.HandshakeSpec>? {
        val node = profile.channel.nodes.firstOrNull()
            ?: features.freeflow.domain.freeFlowBuiltinNodes(profile.channel.familyKey).firstOrNull()
            ?: return null
        return Triple(node.server, node.serverPort, node.handshake)
    }

    private suspend fun saveAndCommit(profile: FreeFlowProfile): Result = try {
        when (val outcome = saveAndCommitInternal(profile)) {
            is Result.Failed -> Result.Failed(outcome.error)
            else -> outcome
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.Failed(error)
    }

    private suspend fun saveAndCommitInternal(profile: FreeFlowProfile): Result {
        val expected = snapshot()
        if (!profile.enabled) {
            profileStore.save(profile)
            return Result.AlreadyApplied
        }
        // 首次启用时快照被免流强制改写的开关原值（移除免流时恢复，如 ROOT IPv6 禁用器）
        val firstEnable = !FreeFlowCompiler.run { expected.freeFlowApplied() } && !currentProfile().enabled
        val effectiveProfile = if (firstEnable) {
            profile.copy(
                restore = features.freeflow.domain.RestoreSpec(
                    enableRootIpv6Disabler = expected.enableRootIpv6Disabler,
                ),
            )
        } else {
            profile
        }
        // 机场透传：strip 前摘出要保留的机场组+成员，编译后放回（订阅拉取/导入的货不丢）
        val preserved = expected.takeAirportSnapshot(
            buildSet {
                profile.airports.forEach { airport -> add(airport.name) }
                // 派生组（·国内/·国外）恒随快照保留：纯机场模式的成员载体；
                // 切回其它模式时编译器从派生组把成员播种回源组，随后按模式 prune 清掉派生组
                val activeName = profile.split.activeAirport
                    ?: profile.airports.firstOrNull()?.name
                if (activeName != null) {
                    val (cnName, finalName) = FreeFlowCompiler.derivedAirportGroupNames(activeName)
                    add(cnName)
                    add(finalName)
                }
            },
        )
        val stripped = expected
            .withoutFreeFlowArtifacts()
            .restoreAirportSnapshot(preserved)
        val artifacts = when (val compiled = FreeFlowCompiler.compile(stripped, effectiveProfile)) {
            is FreeFlowCompiler.Result.Success -> compiled.artifacts
            is FreeFlowCompiler.Result.Failed -> return Result.Failed(
                IllegalStateException("免流配置未生效：${compiled.reason}"),
            )
        }
        var final = stripped.withFreeFlowArtifactsApplied(artifacts)
        // 通道外来货透传回填：编辑器手添/订阅拉取的成员（无 ff 标记）跨重编译与换族保留；
        // 回填必须换新 id（旧 id 已被新编译成员复用，原样回填=重复主键，真机闪退根因）；
        // 彩信族强制外来货 detour 骑 WAP 中转（否则直连=全量计费）
        val wapDetourTag = artifacts.outbounds
            .firstOrNull { outbound -> outbound.remarks == features.freeflow.domain.FfWapRemarks }
            ?.let { outbound -> managedOutboundTag(outbound.id, outbound.remarks) }
        final = final.restoreChannelCargo(expected.takeChannelCargoSnapshot(), wapDetourTag)
        final = profile.airports.fold(final) { state, airport ->
            val obfHost = obfHostStore.get(airport.name)
            if (obfHost.isBlank()) return@fold state
            var next = state.withAirportObfHostApplied(airport.name, obfHost)
            // 纯机场模式下派生组同步注入混淆 Host（组员与源组同源）
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly &&
                airport.name == (profile.split.activeAirport ?: profile.airports.firstOrNull()?.name)
            ) {
                val (cnName, finalName) = FreeFlowCompiler.derivedAirportGroupNames(airport.name)
                next = next.withAirportObfHostApplied(cnName, obfHost)
                next = next.withAirportObfHostApplied(finalName, obfHost)
            }
            next
        }
        // 通道成员按手机卡运营商稳定排序（本卡运营商优先，其他殿后；识别不出按默认序）
        final = final.withChannelNodesCarrierOrdered(
            profile.channel.familyKey,
            ffSimCarrierFromOperator(carrierOperator()),
        )
        // 机场成员摘除 detour：历史导入/播种残留的选择器 tag 引用会在组被剪后悬空（启动 FATAL），
        // 统一清掉；链式模式的通道 detour 随后重新注入
        final = final.withoutAirportMemberDetours()
        // 百度链式：生效机场组成员 detour 骑免流通道组（覆盖既有 detour；TCP 链，UDP 无法骑链）
        if (profile.split.mode == features.freeflow.domain.SplitMode.Chained) {
            val chainedAirport = profile.split.activeAirport
                ?.let { name -> profile.airports.firstOrNull { airport -> airport.name == name } }
                ?: profile.airports.firstOrNull()
            if (chainedAirport != null) {
                val channelGroup = final.outboundGroups.first { group ->
                    group.ownerKey == FreeFlowOwnerKey && group.name == FreeFlowCompiler.FreeFlowChannelGroupName
                }
                final = final.withAirportChainDetour(
                    chainedAirport.name,
                    managedOutboundGroupSelectorTag(channelGroup.id, channelGroup.name),
                )
            }
        }
        // 模式切换清理：当前模式不再需要的 owned 组（如纯机场派生的「快跑·国内/·国外」在分流模式下）
        // 连同成员摘除——否则选择器管理页积累上次模式生成的遗留选择器（用户要求每次切换都清）
        val expectedOwnedNames = buildSet {
            if (profile.split.mode == features.freeflow.domain.SplitMode.AirportOnly) {
                // 纯机场：只保留派生组——免流网关/CNS/源组都不参与（源组切回其它模式时播种回填）
                val activeName = profile.split.activeAirport
                    ?: profile.airports.firstOrNull()?.name
                if (activeName != null) {
                    val (cnName, finalName) = FreeFlowCompiler.derivedAirportGroupNames(activeName)
                    add(cnName)
                    add(finalName)
                }
            } else {
                add(FreeFlowCompiler.FreeFlowChannelGroupName)
                if (profile.cnsNodes.isNotEmpty()) add(FreeFlowCompiler.FreeFlowCnsGroupName)
                profile.airports.forEach { airport -> add(airport.name) }
            }
        }
        val staleGroupIds = final.outboundGroups
            .filter { group -> group.ownerKey == FreeFlowOwnerKey && group.name !in expectedOwnedNames }
            .mapTo(mutableSetOf()) { group -> group.id }
        if (staleGroupIds.isNotEmpty()) {
            val removedTags = buildSet {
                final.outboundGroups
                    .filter { group -> group.id in staleGroupIds }
                    .forEach { group -> add(managedOutboundGroupSelectorTag(group.id, group.name)) }
                final.outbounds
                    .filter { outbound -> outbound.groupId in staleGroupIds }
                    .forEach { outbound -> add(managedOutboundTag(outbound.id, outbound.remarks)) }
            }
            final = final.copy(
                outboundGroups = final.outboundGroups.filterNot { group -> group.id in staleGroupIds },
                outbounds = final.outbounds.filterNot { outbound -> outbound.groupId in staleGroupIds },
            ).withRemovedManagedOutboundTags(removedTags)
        }
        validate(final)
        if (!profileStore.save(profile)) {
            return Result.Failed(IllegalStateException("免流档保存失败"))
        }
        return commit(expected, final).fold(
            onSuccess = { committed ->
                if (committed) {
                    Result.Applied(
                        selectorTag = artifacts.routeFinal,
                        nodeCount = final.outbounds.count { outbound ->
                            outbound.groupId == artifacts.groups.firstOrNull()?.id
                        },
                    )
                } else {
                    Result.Failed(IllegalStateException("State changed during free-flow commit"))
                }
            },
            onFailure = Result::Failed,
        )
    }

    // ------------------------------------------------------------- 机场混淆 Host 注入

    /**
     * 机场组混淆 Host（按组名）：节点形态覆盖见 [AirportObfHostPatcher]——普通 http 出站根
     * headers、ws/http/httpupgrade 三种 v2ray 传输层（http 传输 headers.Host 与 host 字段同值）。
     */
    private fun AppState.withAirportObfHostApplied(groupName: String, obfHost: String): AppState {
        val group = outboundGroups.firstOrNull { item ->
            item.ownerKey == FreeFlowOwnerKey && item.name == groupName
        } ?: return this
        val updated = outbounds.map { outbound ->
            if (outbound.groupId != group.id) return@map outbound
            val nextJson = AirportObfHostPatcher.patch(outbound.type, outbound.json, obfHost)
                ?: return@map outbound
            if (nextJson != outbound.json) outbound.copy(json = nextJson) else outbound
        }
        if (updated == outbounds) return this
        return copy(outbounds = updated)
    }
}
