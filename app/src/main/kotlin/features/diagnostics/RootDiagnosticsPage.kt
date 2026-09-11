// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.collectAppState
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdRuleCategory
import engine.root.daemon.control.AsteriskdSnapshot
import engine.root.runtime.RootSupervisorController
import engine.root.runtime.boundSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.asterisk.zcc.abox.R
import ui.layout.pageContentPaddingWithCutout

/**
 * ROOT 运行诊断：守护进程阶段、生效规则分类、网络就绪状态。
 * 数据来自 asteriskd 控制通道快照（此前引擎解析了但没有任何界面消费）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RootDiagnosticsPage(
    padding: PaddingValues,
) {
    val services = LocalAppServices.current
    val navigator = LocalNavigator.current
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(appContext, services.rootAccess) {
        RootSupervisorController(appContext, services.rootAccess)
    }

    var snapshot by remember { mutableStateOf<AsteriskdSnapshot?>(null) }
    var notRunning by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        refreshing = true
        try {
            val result = controller.status()
            snapshot = result.boundSnapshot()
            notRunning = snapshot == null
            errorText = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            snapshot = null
            notRunning = false
            errorText = error.message ?: error::class.simpleName
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // 打开期间 5 秒轮询，规则代数变化（如热点后开自动接管）立即可见
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_root_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(onClick = { scope.launch { refresh() } }, enabled = refreshing.not()) {
                        Text(stringResource(R.string.common_refresh))
                    }
                },
            )
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            errorText != null -> Text(
                                stringResource(R.string.diag_query_failed, errorText.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            notRunning -> Text(
                                stringResource(R.string.diag_not_running),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            snapshot != null -> DiagnosticsContent(snapshot!!)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsContent(snapshot: AsteriskdSnapshot) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(phaseLabel(snapshot.phase), style = MaterialTheme.typography.titleLarge)
        Text(
            modeLabel(snapshot.mode),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    DiagRow(stringResource(R.string.diag_daemon_pid), snapshot.supervisorPid.toString())
    DiagRow(stringResource(R.string.diag_core_pid), snapshot.corePid?.toString() ?: "—")
    snapshot.helperType?.let { helper ->
        DiagRow(
            stringResource(R.string.diag_helper_pid),
            helper.wireValue + " · " + (snapshot.helperPid?.toString() ?: "—"),
        )
    }
    DiagRow(
        stringResource(R.string.diag_matcher),
        when {
            snapshot.matcherActive -> stringResource(R.string.diag_matcher_active)
            snapshot.matcherConfigured -> stringResource(R.string.diag_matcher_configured)
            else -> stringResource(R.string.diag_matcher_off)
        },
    )

    Text(stringResource(R.string.diag_rules_section), style = MaterialTheme.typography.titleSmall)
    Text(
        if (snapshot.rules.active) {
            stringResource(R.string.diag_rules_active_fmt, snapshot.rules.generation)
        } else {
            stringResource(R.string.diag_rules_inactive)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (snapshot.rules.categories.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.rules.categories.forEach { category ->
                AssistChip(onClick = {}, label = { Text(categoryLabel(category)) })
            }
        }
    }

    Text(stringResource(R.string.diag_network_section), style = MaterialTheme.typography.titleSmall)
    DiagRow("IPv4", if (snapshot.network.ipv4Ready) stringResource(R.string.diag_ready) else stringResource(R.string.diag_not_ready))
    DiagRow(
        "IPv6",
        when {
            snapshot.network.ipv6Ready -> stringResource(R.string.diag_ready)
            snapshot.network.ipv6Enabled -> stringResource(R.string.diag_enabled_not_ready)
            else -> stringResource(R.string.diag_ipv6_disabled)
        },
    )

    snapshot.error?.let { error ->
        Text(stringResource(R.string.diag_error_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(
            error.message + (error.code.wireValue.let { code -> " ($code)" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun phaseLabel(phase: AsteriskdPhase): String = when (phase) {
    AsteriskdPhase.Validating -> "校验配置"
    AsteriskdPhase.Acquiring -> "获取权限"
    AsteriskdPhase.Starting -> "启动中"
    AsteriskdPhase.ApplyingRules -> "应用规则"
    AsteriskdPhase.Running -> "运行中"
    AsteriskdPhase.Stopping -> "停止中"
    AsteriskdPhase.Stopped -> "已停止"
    AsteriskdPhase.Failed -> "失败"
}

private fun modeLabel(mode: engine.root.daemon.config.AsteriskdMode): String = when (mode) {
    engine.root.daemon.config.AsteriskdMode.Tproxy -> "TPROXY"
    engine.root.daemon.config.AsteriskdMode.Tun -> "TUN"
    engine.root.daemon.config.AsteriskdMode.Tun2Socks -> "TUN2SOCKS"
    engine.root.daemon.config.AsteriskdMode.Bpf2Socks -> "BPF2SOCKS"
    engine.root.daemon.config.AsteriskdMode.Ebpf -> "EBPF"
}

private fun categoryLabel(category: AsteriskdRuleCategory): String = when (category) {
    AsteriskdRuleCategory.Tproxy -> "TPROXY 接管"
    AsteriskdRuleCategory.Routing -> "策略路由"
    AsteriskdRuleCategory.Dns -> "DNS 劫持"
    AsteriskdRuleCategory.FakeDns -> "FakeIP 解析"
    AsteriskdRuleCategory.LocalBypass -> "本地直连例外"
    AsteriskdRuleCategory.Hotspot -> "热点接管"
    AsteriskdRuleCategory.Tc -> "TC 流量接管"
    AsteriskdRuleCategory.Bpf -> "BPF 匹配"
    AsteriskdRuleCategory.Ipv6Guard -> "IPv6 守卫"
}
