// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import features.logs.AndroidAppLogger
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.effects.ProxyStatusSynchronizer
import app.effects.ResourceFileSynchronizer
import app.effects.RootBootScriptSynchronizer
import app.effects.SingBoxRuntimeSynchronizer
import app.effects.TrafficStatsNotificationSynchronizer
import data.backup.AndroidAppBackupDocumentGateway
import data.backup.AppBackupUseCase
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyServiceUseCase
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidAsteriskdLogRepository
import features.logs.AndroidLogcatRepository
import features.freeflow.usecase.FreeFlowUseCase
import features.monitoring.traffic.DirectTrafficCounter
import features.outbound.OutboundCommandResult
import features.monitoring.MonitoringRepository
import features.resources.ResourceFileUpdateCoordinator
import features.resources.ResourceFileUpdateRequest
import features.resources.ResourceFileUseCase
import features.resources.runtime.AndroidResourceFileDownloadCancellation
import features.settings.locale.ProvideAppLanguage
import features.settings.usecase.RootBootScriptUseCase
import features.settings.usecase.RootEbpfProbeUseCase
import features.settings.usecase.SwitchRunModeUseCase
import features.settings.usecase.ApplyServiceControlUseCase
import system.AndroidNetworkInterfaceProvider
import system.AndroidPackageProvider
import system.AndroidRootShellGateway
import system.AndroidUserSpaceProvider
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ui.AppTheme
import ui.feedback.AndroidToastTipNotifier
import ui.keyColorFor

@Composable
fun App(
    padding: PaddingValues = PaddingValues(0.dp),
    qrCodeScanner: suspend () -> String?,
    resourceFilePicker: suspend () -> Uri?,
    backupFilePicker: suspend () -> Uri?,
    backupFileCreator: suspend (String) -> Uri?,
    logFileCreator: suspend (String) -> Uri?,
    requestVpnPermission: suspend (Intent) -> Boolean,
) {
    val appContext = LocalContext.current.applicationContext
    val systemUiSnapshot = appContext.currentSystemUiSnapshot()
    val application = appContext as AsteriskApplication
    val appScope = application.appScope
    val rootAccess = remember { AndroidRootShellGateway() }
    val stateStore = remember(application) { application.stateStore }
    val userSpaces = remember(appContext, rootAccess) {
        AndroidUserSpaceProvider(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val packageCatalog = remember(appContext, rootAccess, userSpaces) {
        AndroidPackageProvider(
            context = appContext,
            rootAccess = rootAccess,
            userSpaces = userSpaces,
        )
    }
    val networkInterfaces = remember(rootAccess) {
        AndroidNetworkInterfaceProvider(rootAccess)
    }
    val resourceFileUseCase = remember(appContext, resourceFilePicker, rootAccess, stateStore) {
        ResourceFileUseCase(
            context = appContext,
            resourceFilePicker = resourceFilePicker,
            currentAppState = { stateStore.state.value },
            rootShell = rootAccess,
        )
    }
    val appBackupUseCase = remember(appContext, backupFilePicker, backupFileCreator) {
        AppBackupUseCase(
            gateway = AndroidAppBackupDocumentGateway(
                context = appContext,
                filePicker = backupFilePicker,
                fileCreator = backupFileCreator,
            ),
        )
    }
    val resourceFileUpdateCoordinator = remember(appScope, resourceFileUseCase) {
        ResourceFileUpdateCoordinator(
            scope = appScope,
            execute = { request ->
                when (request) {
                    is ResourceFileUpdateRequest.BuiltIn -> resourceFileUseCase.update(
                        kind = request.kind,
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.Custom -> resourceFileUseCase.updateCustom(
                        customFile = request.file,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.CustomBatch -> resourceFileUseCase.updateCustomBatch(
                        customFiles = request.files,
                        options = request.options,
                        allCustomResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.All -> resourceFileUseCase.update(
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                }
            },
            cancelRunning = AndroidResourceFileDownloadCancellation::cancel,
        )
    }
    val outboundSubscriptionUpdater =
        remember(application) { application.outboundSubscriptionUpdater }
    val singBoxRuntime = application.singBoxRuntime
    val outboundPingRuntime = remember(application) { application.outboundPingRuntime }
    val outboundRepository = remember(application) { application.outboundRepository }
    val outboundListProjectionCache = remember(application) { application.outboundListProjectionCache }
    val monitoring = remember(appScope, appContext, rootAccess, stateStore, singBoxRuntime) {
        MonitoringRepository(appScope, appContext, rootAccess, stateStore, singBoxRuntime)
    }
    val directTrafficCounter = remember(appScope, singBoxRuntime, appContext) {
        DirectTrafficCounter(
            appScope = appScope,
            singBoxRuntime = singBoxRuntime,
            prefs = appContext.getSharedPreferences("mtl_direct_stats", android.content.Context.MODE_PRIVATE),
        )
    }
    val proxyEngine = remember(appContext, rootAccess) {
        AndroidProxyEngine(
            context = appContext,
            rootAccess = rootAccess,
            requestVpnPermission = requestVpnPermission,
        )
    }
    val rootBootScriptUseCase = remember(appContext, rootAccess) {
        RootBootScriptUseCase(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val rootEbpfProbeUseCase = remember(appContext, rootAccess) {
        RootEbpfProbeUseCase(
            context = appContext,
            rootAccess = rootAccess,
        )
    }
    val switchRunModeUseCase = remember(proxyEngine, rootAccess, rootBootScriptUseCase) {
        SwitchRunModeUseCase(
            context = appContext,
            proxyEngine = proxyEngine,
            rootAccess = rootAccess,
            rootBootScriptUseCase = rootBootScriptUseCase,
        )
    }
    val proxyServiceUseCase = remember(proxyEngine) {
        ProxyServiceUseCase(proxyEngine)
    }
    val freeFlowUseCase = remember(appContext, stateStore) {
        features.freeflow.usecase.FreeFlowUseCase(
            snapshot = { stateStore.state.value },
            commit = { expected, updated ->
                stateStore.commitPreparedAndAwaitPersistence(expected, updated).also { result ->
                    // 服务运行中提交免流变更后热重载引擎：否则运行时仍是旧配置，
                    // 代理页 tag 与新状态对不上、隧道也依旧配置（I10，mtl15 教训）
                    if (result.isSuccess && updated.proxyRunning) {
                        runCatching {
                            proxyEngine.restart(ProxyEngineStartRequest(updated))
                        }.onFailure { reloadError ->
                            // 新配置已落盘但隧道仍是旧配置：静默吞掉会造持久的配置漂移，
                            // 必须显式留痕并指引手动重开（审计 high）
                            AndroidAppLogger.error(
                                "FreeFlowHotReload",
                                "热重载失败：新配置已保存但隧道仍运行旧配置，请手动关闭再开启代理",
                                reloadError,
                            )
                        }
                    }
                }
            },
            validate = { state ->
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(appContext, state)
                }
            },
            profileStore = features.freeflow.store.FreeFlowProfileStore(stateStore),
            obfHostStore = features.freeflow.store.FreeFlowObfHostStore(appContext),
            probeArchive = features.freeflow.store.FreeFlowProbeArchiveStore(appContext),
            rejectStats = features.freeflow.store.GatewayRejectStatsStore(appContext),
            prober = features.freeflow.probe.AndroidGatewayProber(appContext)::probe,
            carrierOperator = {
                runCatching {
                    (appContext.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                        ?.simOperator
                }.getOrNull().orEmpty()
            },
            fetchGroupNodes = { groupId ->
                // 机场「主动获取节点」：复用订阅更新器手动拉取管线（下载/解析/CAS 落库同一套）
                when (val result = outboundSubscriptionUpdater.update(
                    groupId = groupId,
                    trigger = features.subscription.usecase.SubscriptionUpdateTrigger.MANUAL,
                )) {
                    features.subscription.usecase.OutboundSubscriptionUpdateResult.NotModified ->
                        FreeFlowUseCase.AirportNodesFetchSummary(importedCount = 0, allUnchanged = true)
                    is features.subscription.usecase.OutboundSubscriptionUpdateResult.Success ->
                        FreeFlowUseCase.AirportNodesFetchSummary(
                            importedCount = result.outcome.accepted.size,
                            allUnchanged = false,
                        )
                    is features.subscription.usecase.OutboundSubscriptionUpdateResult.Partial ->
                        FreeFlowUseCase.AirportNodesFetchSummary(
                            importedCount = result.outcome.accepted.size,
                            allUnchanged = false,
                        )
                    is features.subscription.usecase.OutboundSubscriptionUpdateResult.Failed ->
                        throw result.error
                    is features.subscription.usecase.OutboundSubscriptionUpdateResult.Cancelled ->
                        throw IllegalStateException(result.reason)
                }
            },
        )
    }
    val applyServiceControlUseCase = remember(proxyEngine) {
        ApplyServiceControlUseCase(proxyEngine)
    }
    val tipNotifier = remember(appContext) { AndroidToastTipNotifier(appContext) }
    val services = remember(
        appScope,
        proxyEngine,
        rootAccess,
        userSpaces,
        packageCatalog,
        networkInterfaces,
        resourceFileUseCase,
        resourceFileUpdateCoordinator,
        appBackupUseCase,
        outboundSubscriptionUpdater,
        qrCodeScanner,
        resourceFilePicker,
        backupFilePicker,
        backupFileCreator,
        singBoxRuntime,
        outboundPingRuntime,
        outboundRepository,
        outboundListProjectionCache,
        monitoring,
        proxyServiceUseCase,
        freeFlowUseCase,
        switchRunModeUseCase,
        applyServiceControlUseCase,
        rootBootScriptUseCase,
        rootEbpfProbeUseCase,
        tipNotifier,
        logFileCreator,
    ) {
        AppServices(
            appScope = appScope,
            proxyEngine = proxyEngine,
            rootAccess = rootAccess,
            userSpaces = userSpaces,
            packageCatalog = packageCatalog,
            networkInterfaces = networkInterfaces,
            resourceFileUseCase = resourceFileUseCase,
            resourceFileUpdateCoordinator = resourceFileUpdateCoordinator,
            appBackupUseCase = appBackupUseCase,
            outboundSubscriptionUpdater = outboundSubscriptionUpdater,
            qrCodeScanner = qrCodeScanner,
            importFilePicker = resourceFilePicker,
            backupFilePicker = backupFilePicker,
            backupFileCreator = backupFileCreator,
            singBoxRuntime = singBoxRuntime,
            outboundPingRuntime = outboundPingRuntime,
            outboundRepository = outboundRepository,
            outboundListProjectionCache = outboundListProjectionCache,
            monitoring = monitoring,
            directTrafficCounter = directTrafficCounter,
            proxyServiceUseCase = proxyServiceUseCase,
            freeFlowUseCase = freeFlowUseCase,
            switchRunModeUseCase = switchRunModeUseCase,
            applyServiceControlUseCase = applyServiceControlUseCase,
            rootBootScriptUseCase = rootBootScriptUseCase,
            rootEbpfProbeUseCase = rootEbpfProbeUseCase,
            tipNotifier = tipNotifier,
            logFileCreator = logFileCreator,
            coreLogRepository = AndroidCoreLogRepository,
            rootLogRepository = AndroidAsteriskdLogRepository,
            logcatRepository = AndroidLogcatRepository,
        )
    }
    val chromeState by stateStore.collectAppChromeState()
    val updateAppState: ((AppState) -> AppState) -> Unit = remember(stateStore) {
        { transform -> stateStore.update(transform) }
    }
    val keyColor = keyColorFor(chromeState.seedIndex)
    ProxyStatusSynchronizer(
        stateStore = stateStore,
        proxyEngine = proxyEngine,
        updateAppState = updateAppState,
    )
    SingBoxRuntimeSynchronizer(
        stateStore = stateStore,
        singBoxRuntime = application.singBoxRuntime,
    )
    ResourceFileSynchronizer(
        resourceFileUseCase = resourceFileUseCase,
        stateStore = stateStore,
    )
    RootBootScriptSynchronizer(
        stateStore = stateStore,
        rootBootScriptUseCase = rootBootScriptUseCase,
    )
    TrafficStatsNotificationSynchronizer(
        stateStore = stateStore,
    )
    // N9: 进程重启(系统回收/内核崩溃)后持久态仍为运行中则自动恢复隧道,
    // 收敛跳点分析 J4 全量直连窗口; onRevoke(他 VPN 抢占)不走此路径
    LaunchedEffect(stateStore, proxyEngine) {
        val persisted = stateStore.state.value
        if (!persisted.proxyRunning) return@LaunchedEffect
        val status = proxyEngine.status(appState = persisted)
        if (!status.running) {
            runCatching { proxyEngine.start(ProxyEngineStartRequest(persisted)) }
                .onFailure { error ->
                    AndroidAppLogger.warn("AutoResume", "Auto resume tunnel failed: ${error.message}")
                }
        }
    }
    // P0 直连观测（跳点计数）：代理运行期间 5 秒轮询连接表，累计 direct 出站增量
    LaunchedEffect(directTrafficCounter) {
        directTrafficCounter.start { stateStore.state.value }
    }
    // 免流一次性迁移：旧 features/mtl 模板产物 → FreeFlowProfile 声明档（幂等）
    LaunchedEffect(stateStore, freeFlowUseCase) {
        runCatching { freeFlowUseCase.migrateIfNeeded() }
            .onFailure { error ->
                AndroidAppLogger.warn("FreeFlow", "Free-flow migration failed: ${error.message}")
            }
    }

    ProvideAppLanguage(
        languageMode = chromeState.languageMode,
        systemLocale = systemUiSnapshot.locale,
    ) {
        AppTheme(
            colorMode = chromeState.colorMode,
            keyColor = keyColor,
            systemDark = systemUiSnapshot.isDark,
        ) {
            CompositionLocalProvider(
                LocalAppStateStore provides stateStore,
                LocalAppChromeState provides chromeState,
                LocalUpdateAppState provides updateAppState,
                LocalAppServices provides services,
            ) {
                AppContent(padding = padding)
            }
        }
    }
}
