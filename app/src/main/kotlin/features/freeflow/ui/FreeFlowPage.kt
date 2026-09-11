// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import features.freeflow.compile.freeFlowFailClosed
import utils.toReadableBytes
import androidx.compose.runtime.collectAsState
import features.freeflow.compile.freeFlowFailClosed
import utils.toReadableBytes
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.collectAppState
import features.freeflow.compile.FreeFlowCompiler
import features.freeflow.compile.freeFlowApplied
import features.freeflow.domain.CnsSpec
import features.freeflow.domain.FreeFlowDefaultSubscriptionUrl
import features.freeflow.domain.FreeFlowFamilies
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.GatewayProfile
import features.freeflow.domain.GatewayVerdict
import features.freeflow.domain.SplitMode
import features.freeflow.domain.UdpPolicy
import features.freeflow.domain.freeFlowBuiltinNodes
import features.freeflow.domain.freeFlowFamilyByKey
import features.freeflow.usecase.FreeFlowUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.asterisk.zcc.abox.R
import ui.layout.pageContentPaddingWithCutout

/**
 * 免流页 v2：状态头（族名大字 + 徽标 + 节点/订阅摘要）→ 通道 → 策略 → 诊断（折叠）；
 * 套用/移除吸底常驻；CNS/机场编辑收进底部弹层，主页只留条目行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeFlowPage(
    padding: PaddingValues,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val services = LocalAppServices.current
    val useCase = services.freeFlowUseCase
    val tipNotifier = services.tipNotifier
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf(false) }
    var profileVersion by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf<FreeFlowProfile?>(null) }
    var probeResult by remember { mutableStateOf<GatewayProfile?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var cnsEditor by remember { mutableStateOf(false) }
    var airportEditor by remember { mutableStateOf(false) }
    var fetchingAirports by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(profileVersion) {
        profile = useCase.currentProfile()
    }

    val context = LocalContext.current
    val appliedMessage = stringResource(R.string.ff_toast_applied)
    val removedMessage = stringResource(R.string.ff_toast_removed)
    val failedMessage = stringResource(R.string.ff_toast_failed)
    val exportedMessage = stringResource(R.string.ff_archive_exported)

    // 配置存档走 MainActivity 的 SAF 桥（backupFileCreator/backupFilePicker）——
    // 免流页组合层没有 ActivityResultRegistryOwner，compose 版注册器在此直接崩页

    suspend fun run(op: suspend () -> FreeFlowUseCase.Result) {
        working = true
        try {
            when (val result = op()) {
                is FreeFlowUseCase.Result.Applied -> tipNotifier.show(appliedMessage)
                is FreeFlowUseCase.Result.AlreadyApplied -> Unit
                is FreeFlowUseCase.Result.Failed ->
                    tipNotifier.show(result.error.message ?: failedMessage)
            }
            profileVersion += 1
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 兜底：用例层任何漏网异常都降级为提示，不准带崩整个应用
            tipNotifier.show(error.message ?: failedMessage)
        } finally {
            working = false
        }
    }

    // 机场「主动获取节点」：按机场名拉订阅（含纯机场派生组），只拉取不重编译，
    // 新节点要「套用」后才进运行中的隧道（与代理页手动更新同语义）
    suspend fun fetchAirportNodes(name: String) {
        if (name in fetchingAirports) return
        fetchingAirports = fetchingAirports + name
        try {
            val summary = useCase.fetchAirportNodes(name)
            tipNotifier.show(
                when {
                    summary.allUnchanged -> context.getString(R.string.ff_airport_fetch_unchanged)
                    else -> context.getString(R.string.ff_airport_fetch_done_fmt, summary.importedCount)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            tipNotifier.show(
                context.getString(R.string.ff_airport_fetch_failed_fmt, error.message ?: failedMessage),
            )
        } finally {
            fetchingAirports = fetchingAirports - name
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ff_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 吸底操作条：长页滚动时套用/移除不丢失
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { scope.launch { run { useCase.applyTemplate() } } },
                        enabled = working.not(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.ff_apply))
                    }
                    OutlinedButton(
                        onClick = { confirmRemove = true },
                        enabled = working.not() && FreeFlowCompiler.run { appState.freeFlowApplied() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.ff_remove))
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = LocalIsWideScreen.current,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusHeader(
                    profile = profile,
                    applied = FreeFlowCompiler.run { appState.freeFlowApplied() },
                )
            }
            item {
                ChannelBlock(
                    profile = profile,
                    enabled = working.not(),
                    onFamily = { key -> scope.launch { run { useCase.setChannelFamily(key) } } },
                    onSubscription = { url -> scope.launch { run { useCase.setChannelSubscription(url) } } },
                )
            }
            item {
                PolicyBlock(
                    profile = profile,
                    working = working,
                    onSplitMode = { mode, active -> scope.launch { run { useCase.setSplitMode(mode, active) } } },
                    onUdpPolicy = { policy -> scope.launch { run { useCase.setUdpPolicy(policy) } } },
                    onQqWechatAllow = { value -> scope.launch { run { useCase.setQqWechatUdpAllow(value) } } },
                    onTelemetry = { value -> scope.launch { run { useCase.setTelemetryEnabled(value) } } },
                    agriBankVisible = profile?.split?.mode != SplitMode.AirportOnly,
                    onAgriBankCompat = { value -> scope.launch { run { useCase.setAgriBankCompat(value) } } },
                    onGameUdp = { packages -> scope.launch { run { useCase.setGameUdp(packages) } } },
                    onCnsRemove = { name -> scope.launch { run { useCase.removeCns(name) } } },
                    onCnsAdd = { cnsEditor = true },
                    onAirportRemove = { name -> scope.launch { run { useCase.removeAirport(name) } } },
                    onAirportAdd = { airportEditor = true },
                    fetchingAirports = fetchingAirports,
                    onAirportFetch = { name -> scope.launch { fetchAirportNodes(name) } },
                    onObfHost = { name, host -> scope.launch { run { useCase.setAirportObfHost(name, host) } } },
                )
            }
            item {
                ArchiveBlock(
                    working = working,
                    onExport = {
                        scope.launch {
                            working = true
                            try {
                                val json = useCase.exportProfileJson().orEmpty()
                                if (json.isEmpty()) error(failedMessage)
                                val uri = services.backupFileCreator("asteriskbox-freeflow-profile.json")
                                    ?: return@launch
                                context.contentResolver.openOutputStream(uri)?.use { output ->
                                    output.write(json.toByteArray())
                                } ?: error(failedMessage)
                                tipNotifier.show(exportedMessage)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: IllegalStateException) {
                                tipNotifier.show(error.message ?: failedMessage)
                            } finally {
                                working = false
                            }
                        }
                    },
                    onImport = {
                        scope.launch {
                            working = true
                            try {
                                val uri = services.backupFilePicker() ?: return@launch
                                val text = context.contentResolver.openInputStream(uri)
                                    ?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                                when (val result = useCase.importProfileJson(text)) {
                                    is FreeFlowUseCase.Result.Applied -> {
                                        profileVersion += 1
                                        tipNotifier.show(appliedMessage)
                                    }
                                    is FreeFlowUseCase.Result.AlreadyApplied -> Unit
                                    is FreeFlowUseCase.Result.Failed ->
                                        tipNotifier.show(result.error.message ?: failedMessage)
                                }
                            } finally {
                                working = false
                            }
                        }
                    },
                )
            }
            item {
                DiagnosticsBlock(
                    probeResult = probeResult,
                    working = working,
                    useCase = useCase,
                    onProbe = {
                        scope.launch {
                            working = true
                            try {
                                probeResult = useCase.probeGateway()
                            } finally {
                                working = false
                            }
                        }
                    },
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.ff_remove_confirm_title)) },
            text = { Text(stringResource(R.string.ff_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { run { useCase.removeFreeFlow() } }
                }) { Text(stringResource(R.string.ff_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (cnsEditor) {
        CnsEditorSheet(
            onDismiss = { cnsEditor = false },
            onSave = { spec ->
                cnsEditor = false
                scope.launch {
                    run {
                        useCase.addOrUpdateCns(
                            spec.name, spec.server, spec.port.toString(), spec.password,
                            spec.masking, spec.maskHost, spec.prependFree,
                            spec.proxyKey, spec.udpFlag,
                        )
                    }
                }
            },
        )
    }

    if (airportEditor) {
        AirportEditorSheet(
            onDismiss = { airportEditor = false },
            onSave = { name, url, obfHost ->
                airportEditor = false
                scope.launch {
                    run { useCase.addOrUpdateAirport(name, url) }
                    // 保存后立即拉一次节点（拉完再设混淆 Host，重编译会把混淆注入拉回来的成员）
                    if (url.isNotBlank()) {
                        fetchAirportNodes(name)
                    }
                    if (obfHost.isNotBlank()) {
                        run { useCase.setAirportObfHost(name, obfHost) }
                    }
                }
            },
        )
    }
}

/** 状态头：族名大字 + 徽标 + 节点/订阅摘要（无操作按钮，操作吸底）。 */
@Composable
private fun StatusHeader(
    profile: FreeFlowProfile?,
    applied: Boolean,
) {
    val familyTitle = profile
        ?.let { freeFlowFamilyByKey(it.channel.familyKey).title }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (applied && profile?.enabled == true && familyTitle != null) {
                        stringResource(R.string.ff_status_active_fmt, familyTitle)
                    } else {
                        stringResource(R.string.ff_status_inactive)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (applied && profile?.enabled == true) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (applied && profile?.enabled == true) R.string.ff_badge_on else R.string.ff_badge_off,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (profile?.enabled == true) {
                val nodeCount = profile.channel.nodes.ifEmpty {
                    freeFlowBuiltinNodes(profile.channel.familyKey)
                }.size
                Text(
                    text = stringResource(R.string.ff_status_nodes_fmt, nodeCount) + " · " + stringResource(
                        if (profile.channel.subscriptionUrl.isNotBlank()) R.string.ff_status_sub_on else R.string.ff_status_sub_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (applied) {
                // P0 直连观测（跳点计数）：常驻状态卡，代理运行期间 direct 出站增量累计
                val appState by LocalAppStateStore.current.collectAppState()
                val directTrafficCounter = LocalAppServices.current.directTrafficCounter
                val directStats by directTrafficCounter.snapshot.collectAsState()
                val observationEnabled by directTrafficCounter.enabled.collectAsState()
                val clearedAt by directTrafficCounter.clearedAt.collectAsState()
                if (appState.freeFlowFailClosed()) {
                    Text(
                        stringResource(R.string.ff_failclosed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ff_direct_stats_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = observationEnabled,
                        onCheckedChange = { value -> directTrafficCounter.setEnabled(value) },
                    )
                    TextButton(onClick = { directTrafficCounter.reset() }) {
                        Text(stringResource(R.string.ff_direct_stats_reset))
                    }
                }
                if (!observationEnabled) {
                    // 关闭=暂停记录（实时生效），已有数据保留
                    Text(
                        stringResource(R.string.ff_direct_stats_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.ff_direct_stats_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (clearedAt > 0L) {
                        Text(
                            stringResource(
                                R.string.ff_direct_stats_cleared,
                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(clearedAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (directStats.totalBytes == 0L) {
                        Text(
                            stringResource(R.string.ff_direct_stats_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            stringResource(
                                R.string.ff_direct_stats_value,
                                directStats.netUploadBytes.toReadableBytes(),
                                directStats.netDownloadBytes.toReadableBytes(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (directStats.byDesignUploadBytes + directStats.byDesignDownloadBytes > 0L) {
                            Text(
                                stringResource(
                                    R.string.ff_direct_stats_bydesign,
                                    directStats.byDesignUploadBytes.toReadableBytes(),
                                    directStats.byDesignDownloadBytes.toReadableBytes(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            stringResource(
                                R.string.ff_direct_stats_tcp,
                                directStats.tcpUploadBytes.toReadableBytes(),
                                directStats.tcpDownloadBytes.toReadableBytes(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                R.string.ff_direct_stats_udp,
                                directStats.udpUploadBytes.toReadableBytes(),
                                directStats.udpDownloadBytes.toReadableBytes(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        directStats.perAppBytes.entries
                            .sortedByDescending { entry -> entry.value }
                            .take(3)
                            .forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        entry.key,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(entry.value.toReadableBytes(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelBlock(
    profile: FreeFlowProfile?,
    enabled: Boolean,
    onFamily: (String) -> Unit,
    onSubscription: (String) -> Unit,
) {
    var subscriptionUrl by remember(profile) {
        // 默认预填网关订阅（自动拉取最新节点与令牌），用户可清空改用纯内置库
        mutableStateOf(
            profile?.channel?.subscriptionUrl?.takeIf { it.isNotEmpty() }
                ?: FreeFlowDefaultSubscriptionUrl,
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ff_block_channel), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeFlowFamilies.forEach { family ->
                    FilterChip(
                        selected = profile?.channel?.familyKey == family.key,
                        onClick = { onFamily(family.key) },
                        enabled = enabled,
                        label = { Text(family.title) },
                    )
                }
            }
            Text(
                text = freeFlowFamilyByKey(profile?.channel?.familyKey.orEmpty()).cardHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = subscriptionUrl,
                onValueChange = { subscriptionUrl = it },
                label = { Text(stringResource(R.string.ff_subscription_url)) },
                supportingText = { Text(stringResource(R.string.ff_subscription_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onSubscription(subscriptionUrl) }, enabled = enabled) {
                Text(stringResource(R.string.ff_subscription_save))
            }
        }
    }
}

@Composable
private fun PolicyBlock(
    profile: FreeFlowProfile?,
    working: Boolean,
    onSplitMode: (SplitMode, String?) -> Unit,
    onUdpPolicy: (UdpPolicy) -> Unit,
    onQqWechatAllow: (Boolean) -> Unit,
    onTelemetry: (Boolean) -> Unit,
    agriBankVisible: Boolean,
    onAgriBankCompat: (Boolean) -> Unit,
    onGameUdp: (List<String>) -> Unit,
    onCnsRemove: (String) -> Unit,
    onCnsAdd: () -> Unit,
    onAirportRemove: (String) -> Unit,
    onAirportAdd: () -> Unit,
    fetchingAirports: Set<String>,
    onAirportFetch: (String) -> Unit,
    onObfHost: (String, String) -> Unit,
) {
    var gameUdpText by remember(profile) {
        mutableStateOf(profile?.guards?.gameUdpPackages?.joinToString(", ").orEmpty())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ff_block_policy), style = MaterialTheme.typography.titleMedium)

            // 分流模式（说明讲"是否免流"而非"走哪"）
            Text(stringResource(R.string.ff_split_mode), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ff_split_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = profile?.split?.mode == SplitMode.FreeOnly,
                    onClick = { onSplitMode(SplitMode.FreeOnly, null) },
                    enabled = working.not(),
                    label = { Text(stringResource(R.string.ff_split_free_only)) },
                )
                FilterChip(
                    selected = profile?.split?.mode == SplitMode.Chained,
                    onClick = { onSplitMode(SplitMode.Chained, profile?.split?.activeAirport) },
                    enabled = working.not(),
                    label = { Text(stringResource(R.string.ff_split_chained)) },
                )
                FilterChip(
                    selected = profile?.split?.mode == SplitMode.AirportDirect,
                    onClick = { onSplitMode(SplitMode.AirportDirect, profile?.split?.activeAirport) },
                    enabled = working.not(),
                    label = { Text(stringResource(R.string.ff_split_airport_direct)) },
                )
                FilterChip(
                    selected = profile?.split?.mode == SplitMode.AirportOnly,
                    onClick = { onSplitMode(SplitMode.AirportOnly, profile?.split?.activeAirport) },
                    enabled = working.not(),
                    label = { Text(stringResource(R.string.ff_split_airport_only)) },
                )
            }

            // UDP 策略：共同前提先讲清，选项自含去向与代价；纯机场模式下 UDP 固定按地理走机场组，选项不适用
            if (profile?.split?.mode == SplitMode.AirportOnly) {
                Text(stringResource(R.string.ff_udp_policy), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.ff_udp_airport_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.ff_udp_policy), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.ff_udp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UdpPolicy.entries.forEach { policy ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = profile?.udp == policy,
                            onClick = { onUdpPolicy(policy) },
                            enabled = working.not(),
                        )
                        Text(
                            text = stringResource(
                                when (policy) {
                                    UdpPolicy.Strict -> R.string.ff_udp_strict
                                    UdpPolicy.ViaCns -> R.string.ff_udp_via_cns
                                    UdpPolicy.AllowAll -> R.string.ff_udp_allow_all
                                },
                            ),
                        )
                    }
                }
            }
            // QQ/微信放行：通话 UDP 直连白名单开关（免流通道只有 TCP，UDP 无法直接骑网关）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ff_qq_wechat_allow),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = profile?.guards?.qqWechatUdpAllow == true,
                    onCheckedChange = onQqWechatAllow,
                    enabled = working.not(),
                )
            }
            Text(
                stringResource(R.string.ff_qq_wechat_allow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            // CNS 隧道：主页只留条目行，编辑在底部弹层
            Text(stringResource(R.string.ff_cns_title), style = MaterialTheme.typography.titleSmall)
            profile?.cnsNodes?.forEach { node ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${node.name.ifBlank { FreeFlowCompiler.FreeFlowCnsGroupName }} · ${node.server}:${node.port}" +
                            if (node.prependFree) " · " + stringResource(R.string.ff_cns_prepend_free) else "",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onCnsRemove(node.name) }, enabled = working.not()) {
                        Text(stringResource(R.string.ff_remove))
                    }
                }
            }
            TextButton(onClick = onCnsAdd, enabled = working.not()) {
                Text(stringResource(R.string.ff_cns_add))
            }

            HorizontalDivider()
            // 机场：条目行（含混淆 Host 内联保存），添加走弹层
            Text(stringResource(R.string.ff_airport_title), style = MaterialTheme.typography.titleSmall)
            profile?.airports?.forEach { airport ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(airport.name, style = MaterialTheme.typography.bodyMedium)
                            if (airport.url.isNotBlank()) {
                                Text(
                                    airport.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (airport.url.isNotBlank()) {
                            TextButton(
                                onClick = { onAirportFetch(airport.name) },
                                enabled = working.not() && airport.name !in fetchingAirports,
                            ) {
                                Text(
                                    stringResource(
                                        if (airport.name in fetchingAirports) {
                                            R.string.ff_airport_fetching
                                        } else {
                                            R.string.ff_airport_fetch
                                        },
                                    ),
                                )
                            }
                        }
                        TextButton(onClick = { onAirportRemove(airport.name) }, enabled = working.not()) {
                            Text(stringResource(R.string.ff_remove))
                        }
                    }
                    var obfHost by remember(airport.name) { mutableStateOf(String()) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = obfHost,
                            onValueChange = { obfHost = it },
                            label = { Text(stringResource(R.string.ff_airport_obf_host)) },
                            singleLine = true,
                            enabled = working.not(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onObfHost(airport.name, obfHost) },
                            enabled = working.not(),
                        ) { Text(stringResource(R.string.ff_airport_obf_save)) }
                    }
                }
            }
            TextButton(onClick = onAirportAdd, enabled = working.not()) {
                Text(stringResource(R.string.ff_airport_add))
            }

            HorizontalDivider()
            // 例外与降噪
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ff_telemetry), modifier = Modifier.weight(1f))
                Switch(
                    checked = profile?.guards?.telemetryBlockEnabled == true,
                    onCheckedChange = onTelemetry,
                    enabled = working.not(),
                )
            }
            if (agriBankVisible) {
                // 农行兼容实验：TCP 走 CNS 中继（clnc 同款载体），软依赖 CNS 节点；纯机场模式不适用
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ff_agri_bank_compat), modifier = Modifier.weight(1f))
                    Switch(
                        checked = profile?.guards?.agriBankCompatEnabled == true,
                        onCheckedChange = onAgriBankCompat,
                        enabled = working.not(),
                    )
                }
                Text(
                    stringResource(R.string.ff_agri_bank_compat_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profile?.guards?.agriBankCompatEnabled == true && profile.cnsNodes.isEmpty()) {
                    // 软依赖现场提醒：没 CNS 节点本开关不起作用
                    Text(
                        stringResource(R.string.ff_agri_bank_compat_needs_cns),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                value = gameUdpText,
                onValueChange = { gameUdpText = it },
                label = { Text(stringResource(R.string.ff_game_udp)) },
                supportingText = { Text(stringResource(R.string.ff_comma_hint)) },
                singleLine = true,
                enabled = working.not(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    onGameUdp(gameUdpText.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                },
                enabled = working.not(),
            ) { Text(stringResource(R.string.ff_save)) }
        }
    }
}

/** 配置存档：导出/导入免流档 JSON（SAF）。 */
@Composable
private fun ArchiveBlock(
    working: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ff_archive_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ff_archive_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onExport, enabled = working.not()) {
                    Text(stringResource(R.string.ff_archive_export))
                }
                OutlinedButton(onClick = onImport, enabled = working.not()) {
                    Text(stringResource(R.string.ff_archive_import))
                }
            }
        }
    }
}

/** CNS 编辑底部弹层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CnsEditorSheet(
    onDismiss: () -> Unit,
    onSave: (CnsSpec) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf(String()) }
    var server by remember { mutableStateOf(String()) }
    var port by remember { mutableStateOf(String()) }
    var password by remember { mutableStateOf(String()) }
    var proxyKey by remember { mutableStateOf("Meng") }
    var udpFlag by remember { mutableStateOf("httpUDP") }
    var maskHost by remember { mutableStateOf(String()) }
    var masking by remember { mutableStateOf(true) }
    var prependFree by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ff_cns_add), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.ff_cns_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text(stringResource(R.string.ff_cns_server_ip)) }, supportingText = { Text(stringResource(R.string.ff_cns_fill_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.ff_cns_port)) }, supportingText = { Text(stringResource(R.string.ff_cns_fill_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.ff_cns_password)) }, supportingText = { Text(stringResource(R.string.ff_cns_fill_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = proxyKey, onValueChange = { proxyKey = it }, label = { Text(stringResource(R.string.ff_cns_proxy_key)) }, supportingText = { Text(stringResource(R.string.ff_cns_proxy_key_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = udpFlag, onValueChange = { udpFlag = it }, label = { Text(stringResource(R.string.ff_cns_udp_flag)) }, supportingText = { Text(stringResource(R.string.ff_cns_udp_flag_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ff_cns_masking), modifier = Modifier.weight(1f))
                Switch(checked = masking, onCheckedChange = { masking = it })
            }
            Text(
                stringResource(R.string.ff_cns_masking_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = maskHost, onValueChange = { maskHost = it }, label = { Text(stringResource(R.string.ff_cns_mask_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ff_cns_prepend_free), modifier = Modifier.weight(1f))
                Switch(checked = prependFree, onCheckedChange = { prependFree = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onSave(
                            CnsSpec(
                                name = name.trim(),
                                server = server.trim(),
                                port = port.toIntOrNull() ?: 80,
                                password = password,
                                masking = masking,
                                maskHost = maskHost.trim(),
                                prependFree = prependFree,
                                proxyKey = proxyKey.trim(),
                                udpFlag = udpFlag.trim(),
                            ),
                        )
                    },
                    enabled = server.isNotBlank() && password.isNotBlank() &&
                        (port.toIntOrNull() in 1..65535),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ff_save)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

/** 机场编辑底部弹层（名称 + 订阅地址 + 混淆 Host 一次填完）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirportEditorSheet(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, obfHost: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf(String()) }
    var url by remember { mutableStateOf(String()) }
    var obfHost by remember { mutableStateOf(String()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ff_airport_add), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.ff_airport_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.ff_airport_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = obfHost,
                onValueChange = { obfHost = it },
                label = { Text(stringResource(R.string.ff_airport_obf_host)) },
                supportingText = { Text(stringResource(R.string.ff_airport_obf_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSave(name.trim(), url.trim(), obfHost.trim()) },
                    enabled = url.startsWith("http"),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ff_save)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

/** 诊断卡：折叠行（探测入口），展开后见明细；结论与账单提醒常显在展开态。 */
@Composable
private fun DiagnosticsBlock(
    probeResult: GatewayProfile?,
    working: Boolean,
    useCase: FreeFlowUseCase,
    onProbe: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.ff_block_diagnostics),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onProbe, enabled = working.not()) {
                    Text(stringResource(R.string.ff_probe_run))
                }
                TextButton(onClick = { expanded = expanded.not() }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.ff_collapse else R.string.ff_expand,
                        ),
                    )
                }
            }
            if (!expanded) return@Column
            Text(
                stringResource(R.string.ff_probe_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lastServer = probeResult?.server
                ?: useCase.probeArchiveAll().firstOrNull()?.server.orEmpty()
            val shown = probeResult
                ?: lastServer.takeIf { it.isNotBlank() }?.let { useCase.probeArchiveFor(it) }
            shown?.let { archive ->
                Text(
                    stringResource(
                        R.string.ff_probe_verdict_fmt,
                        when (archive.verdict) {
                            GatewayVerdict.AllPass -> stringResource(R.string.ff_verdict_all_pass)
                            GatewayVerdict.WhitelistOnly -> stringResource(R.string.ff_verdict_whitelist)
                            GatewayVerdict.Unreachable -> stringResource(R.string.ff_verdict_unreachable)
                            GatewayVerdict.Unknown -> stringResource(R.string.ff_verdict_unknown)
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.ff_probe_billing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                archive.probes.forEach { (_, entry) ->
                    Text(
                        text = entry.target + " · " + when (entry.status) {
                            "200" -> stringResource(R.string.ff_probe_ok)
                            "403", "503" -> stringResource(R.string.ff_probe_rejected)
                            "timeout" -> stringResource(R.string.ff_probe_timeout)
                            "error" -> stringResource(R.string.ff_probe_error)
                            else -> entry.status
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                useCase.profileSuggestions(archive).forEach { suggestion ->
                    Text(suggestion.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val rejectTop = useCase.rejectStatsTop()
            if (rejectTop.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.ff_reject_stats), style = MaterialTheme.typography.titleSmall)
                rejectTop.forEach { (suffix, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(suffix, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
