// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import org.asterisk.zcc.abox.R
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.singbox.logDirectoryPath
import engine.network.NetworkDefaults
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import engine.vpn.hevtun.HevTunRuntime
import features.logs.AndroidAppLogger
import features.logs.clearServiceLogsAsApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import system.getInstalledApplicationsCompat
import utils.toTrimmedNonEmptyDistinctList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("VpnServicePolicy")
class AsteriskVpnService : VpnService() {
    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var hevTunRuntime: HevTunRuntime? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val libboxPlatform by lazy {
        AndroidLibboxPlatformInterface(this)
    }
    private val libboxRuntime by lazy {
        AndroidLibboxServiceRuntime(libboxPlatform) {
            serviceScope.launch {
                operationMutex.withLock {
                    stopVpn()
                    stopSelfOnMain()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AsteriskVpnServiceIntents.ACTION_STOP -> {
                serviceScope.launch {
                    try {
                        operationMutex.withLock {
                            stopVpn()
                        }
                    } finally {
                        stopSelfOnMain(startId)
                    }
                }
            }

            AsteriskVpnServiceIntents.ACTION_START -> {
                val config = intent.readVpnServiceStartConfig()
                if (config == null) {
                    completeStart(Result.failure(IllegalStateException(getString(R.string.error_vpn_start_config_missing))))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    operationMutex.withLock {
                        runCatching {
                            startVpn(config)
                        }.onSuccess {
                            completeStart(Result.success(Unit))
                        }.onFailure { error ->
                            AndroidAppLogger.error(LogTag, "Failed to start VPN Service", error)
                            stopVpn()
                            completeStart(Result.failure(error))
                            stopSelfOnMain(startId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 主线程不做长阻塞（10s 等待会 ANR）；内核停机放后台线程，进程存活期间仍会确认完成
        stopVpnBoundedAsync("destroy")
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpnBoundedAsync("revoke")
        super.onRevoke()
    }

    /**
     * 后台停机（onDestroy/onRevoke 专用）：持互斥锁 + 停机代数守卫。
     * 审计修复：晚到的停机线程若在新的 ACTION_START 完成后继续执行，会关掉新内核、
     * 新 TUN fd、清掉全局 running/LocalProxyRuntime——所以拿到锁后先比对代数，
     * 代数已前移说明新实例已接管，本次停机整体放弃。
     */
    private fun stopVpnBoundedAsync(reason: String) {
        val stopGeneration = startGeneration.get()
        Thread({
            runCatching {
                // 普通线程拿不到挂起版 withLock：tryLock 抢不到锁说明 start/stop 正在
                // 进行，代数必然前移，直接放弃本次停机即可
                if (!operationMutex.tryLock()) {
                    AndroidAppLogger.info(LogTag, "Skipped $reason stop (engine busy)")
                    return@runCatching
                }
                try {
                    if (startGeneration.get() != stopGeneration) {
                        AndroidAppLogger.info(LogTag, "Skipped stale $reason stop (generation moved)")
                        return@runCatching
                    }
                    stopVpn()
                } finally {
                    operationMutex.unlock()
                }
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to stop VPN Service on $reason", error)
            }
        }, "$LogTag-async-stop").apply { isDaemon = true }.start()
    }

    private fun stopSelfOnMain(startId: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopSelf(startId)
        } else {
            mainHandler.post { stopSelf(startId) }
        }
    }

    private fun stopSelfOnMain() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopSelf()
        } else {
            mainHandler.post { stopSelf() }
        }
    }

    private fun startVpn(config: VpnServiceStartConfig) {
        // 代数前移必须先于任何破坏性操作：在途的旧停机线程拿到锁后发现代数变了会自行放弃
        startGeneration.incrementAndGet()
        // 启动守卫：旧内核未确认退出时拒绝启动，宁可失败也不叠加僵尸实例（内存 600MB→1GB 事故根因）
        if (!stopVpn()) {
            error(getString(R.string.error_vpn_previous_runtime_busy))
        }
        val instanceId = startCounter.incrementAndGet()
        AndroidAppLogger.info(LogTag, "Starting VPN instance #$instanceId mode=libbox-hev=${config.hevSocks5TunnelConfig != null}")
        clearServiceLogsAsApp(File(config.coreLogPaths.logDirectoryPath()), LogTag)
        val hevConfig = config.hevSocks5TunnelConfig
        if (hevConfig == null) {
            libboxRuntime.start(config)
        } else {
            val tunDescriptor = establishTun(config)
            tunFileDescriptor = tunDescriptor
            val tunFd = tunFileDescriptor?.fd ?: error(getString(R.string.error_vpn_tun_fd_unavailable))
            libboxRuntime.start(config)
            val runtime = hevTunRuntime ?: HevTunRuntime().also { hevTunRuntime = it }
            runtime.start(hevConfig, tunFd)
            AndroidAppLogger.info(LogTag, "Started Hev TUN with VPN file descriptor")
        }
        if (config.localProxyOptions.port > 0) {
            LocalProxyRuntime.update(config.localProxyOptions)
        } else {
            LocalProxyRuntime.clear()
        }
        running = true
    }

    private fun establishTun(config: VpnServiceStartConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.ipv4Address, config.ipv4PrefixLength)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // v6 地址/路由常驻: 关 IPv6 也要收 v6 进隧道(路由规则拒), 防物理旁路泄露
        if (config.ipv6Address != null) {
            builder
                .addAddress(config.ipv6Address, config.ipv6PrefixLength)
        }

        builder.applyVpnRoutes(config)

        if (config.enableLocalDns) {
            config.dnsServers.forEach { dnsServer ->
                builder.addDnsServer(dnsServer)
            }
        }

        builder.applyApplicationPolicy(config)
        builder.applyAppendHttpProxy(config)

        return builder.establish() ?: error(getString(R.string.error_vpn_tunnel_establish_failed))
    }

    private fun Builder.applyVpnRoutes(config: VpnServiceStartConfig): Builder {
        addRoute(NetworkDefaults.IPV4_ANY_ADDRESS, 0)
        // v6 地址/路由常驻: 关 IPv6 也要收 v6 进隧道(路由规则拒), 防物理旁路泄露
        if (config.ipv6Address != null) {
            addRoute(NetworkDefaults.IPV6_ANY_ADDRESS, 0)
        }
        return this
    }

    private fun Builder.applyApplicationPolicy(config: VpnServiceStartConfig): Builder {
        val policy = config.applicationPolicy
        val selfPackageName = packageName
        when (policy.mode) {
            ProxyAppListModeWhitelist -> {
                val allowedCount = addAllowedApplications(policy.packageNames.filterNot { it.trim() == selfPackageName })
                if (allowedCount == 0) {
                    // An empty allowed list means "all apps" to Android, so use a full deny list instead.
                    addDisallowedApplications(installedPackageNames())
                }
            }

            ProxyAppListModeBlacklist -> {
                addDisallowedApplications(policy.packageNames + selfPackageName)
            }

            ProxyAppListModeGlobal -> {
                addDisallowedApplications(listOf(selfPackageName))
            }

            else -> Unit
        }
        AndroidAppLogger.info(LogTag, "Excluded self package from VPN routing: $selfPackageName")
        return this
    }

    private fun Builder.applyAppendHttpProxy(config: VpnServiceStartConfig): Builder {
        if (config.appendHttpProxyOptions.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setHttpProxy(ProxyInfo.buildDirectProxy(LocalProxyLoopbackAddress, config.appendHttpProxyOptions.port))
            AndroidAppLogger.info(
                LogTag,
                "Appended VPN HTTP proxy: $LocalProxyLoopbackAddress:${config.appendHttpProxyOptions.port}",
            )
        }
        return this
    }

    private fun Builder.addAllowedApplications(packageNames: List<String>): Int {
        return packageNames.toTrimmedNonEmptyDistinctList().count { packageName ->
            addApplicationIfInstalled(packageName) {
                addAllowedApplication(packageName)
            }
        }
    }

    private fun Builder.addDisallowedApplications(packageNames: List<String>): Int {
        return packageNames.toTrimmedNonEmptyDistinctList().count { packageName ->
            addApplicationIfInstalled(packageName) {
                addDisallowedApplication(packageName)
            }
        }
    }

    private fun addApplicationIfInstalled(packageName: String, addApplication: () -> Unit): Boolean {
        return runCatching {
            addApplication()
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                if (error is PackageManager.NameNotFoundException) {
                    false
                } else {
                    throw error
                }
            },
        )
    }

    private fun installedPackageNames(): List<String> {
        return packageManager.getInstalledApplicationsCompat()
            .map { applicationInfo -> applicationInfo.packageName }
            .distinct()
    }

    /**
     * 停机并等待确认；返回是否全部内核在超时内干净退出。
     * false = 可能残留旧实例（僵尸内核），调用方不得接着启动新实例。
     */
    private fun stopVpn(): Boolean {
        val confirmed = stopNativeRuntimesBounded()
        runCatching {
            tunFileDescriptor?.close()
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to close VPN TUN file descriptor", error)
        }
        tunFileDescriptor = null
        LocalProxyRuntime.clear()
        running = false
        return confirmed
    }

    private fun stopNativeRuntimesBounded(): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val confirmed = AtomicBoolean(true)
        val tasks = buildList {
            add("Hev TUN" to { hevTunRuntime?.stop() })
            add("sing-box" to { libboxRuntime.stop() })
        }
        val completion = CountDownLatch(tasks.size)
        val threads = tasks.map { (name, action) ->
            Thread({
                runCatching {
                    action()
                }.onFailure { error ->
                    confirmed.set(false)
                    AndroidAppLogger.warn(LogTag, "Failed to stop $name while stopping VPN Service", error)
                }.also {
                    completion.countDown()
                }
            }, "$LogTag-$name").apply {
                isDaemon = true
            }.also { thread ->
                thread.start()
            }
        }

        if (!completion.await(RuntimeShutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
            confirmed.set(false)
            threads.filter(Thread::isAlive).forEach { thread ->
                thread.interrupt()
                AndroidAppLogger.warn(
                    LogTag,
                    "Timed out stopping VPN runtime after ${RuntimeShutdownTimeoutMillis}ms: ${thread.name}",
                )
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (confirmed.get()) {
            AndroidAppLogger.info(LogTag, "VPN runtimes stopped cleanly in ${elapsed}ms")
        } else {
            AndroidAppLogger.warn(LogTag, "VPN runtime shutdown UNCONFIRMED after ${elapsed}ms; zombie instance possible")
        }
        return confirmed.get()
    }

    companion object {
        private const val LogTag = "AsteriskVpnService"

        /** 停机确认上限：内核 Close 大量连接需要时间，1 秒赌局是僵尸实例的来源。 */
        private const val RuntimeShutdownTimeoutMillis = 10_000L
        private val startCounter = AtomicLong(0L)
        private val startGeneration = AtomicLong(0L)

        @Volatile
        private var running = false

        @Volatile
        private var pendingStart: CompletableDeferred<Result<Unit>>? = null

        internal suspend fun start(context: Context, config: VpnServiceStartConfig) {
            val result = CompletableDeferred<Result<Unit>>()
            pendingStart = result
            try {
                context.startService(AsteriskVpnServiceIntents.startIntent(context, config))
                // 必须宽于内部停机预算（10s）：否则慢关内核时调用方先超时取消，
                // 而服务端其实启动成功——调用方收到的是取消异常且 UI 状态失同步（审计 high）。
                withTimeout(30_000.milliseconds) {
                    result.await()
                }.getOrThrow()
            } finally {
                if (pendingStart === result) {
                    pendingStart = null
                }
            }
        }

        internal fun stop(context: Context) {
            running = false
            context.startService(AsteriskVpnServiceIntents.stopIntent(context))
        }

        internal fun isRunning(): Boolean {
            return running
        }

        private fun completeStart(result: Result<Unit>) {
            pendingStart?.complete(result)
            pendingStart = null
        }

    }
}
