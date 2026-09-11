// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.monitoring.traffic

import android.content.SharedPreferences
import app.AppState
import app.ManagedDirectOutboundTag
import engine.singbox.runtime.SingBoxConnection
import engine.singbox.runtime.SingBoxRuntimeRepository
import features.freeflow.compile.FreeFlowCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * P0 直连观测（跳点计数）：轮询连接表，对最终出站/链上含 direct 的连接做增量累计，
 * TCP/UDP 分协议统计 + 设计内直连（QQ/微信通话、NTP）分列，净额=总直连−设计内。
 * 采样口径=近似：两次轮询之间开合的短连接漏计；本轮代理运行期累计，reset 清零；
 * 观测开关关闭期间不记录（已有数据保留）。
 */
data class DirectTrafficSnapshot(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val tcpUploadBytes: Long = 0L,
    val tcpDownloadBytes: Long = 0L,
    val udpUploadBytes: Long = 0L,
    val udpDownloadBytes: Long = 0L,
    val byDesignUploadBytes: Long = 0L,
    val byDesignDownloadBytes: Long = 0L,
    val perAppBytes: Map<String, Long> = emptyMap(),
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
    val netUploadBytes: Long get() = uploadBytes - byDesignUploadBytes
    val netDownloadBytes: Long get() = downloadBytes - byDesignDownloadBytes
}

/**
 * 纯逻账本：直连连接的 live/closed 状态与字节累计，TCP/UDP 分桶 + 设计内直连分列
 * （无运行时依赖，JVM 可测）。按包名的 Top 榜只计意外直连（净额），设计内不进榜。
 */
internal class DirectTrafficLedger {

    private data class Record(
        val process: String,
        val uploadBytes: Long,
        val downloadBytes: Long,
        val network: String,
        val byDesign: Boolean,
    )

    private val live = LinkedHashMap<String, Record>()
    private val closedPerApp = LinkedHashMap<String, Long>()
    private val closed = LongArray(6) // [tcpUp, tcpDown, udpUp, udpDown, designUp, designDown]
    private val mutableSnapshot = MutableStateFlow(DirectTrafficSnapshot())

    val snapshot: StateFlow<DirectTrafficSnapshot> = mutableSnapshot

    fun reset() {
        live.clear()
        closedPerApp.clear()
        java.util.Arrays.fill(closed, 0L)
        emit()
    }

    fun onPoll(connections: List<SingBoxConnection>) {
        val present = connections.map(SingBoxConnection::id).toSet()
        // 从快照消失=连接已关闭：最后一次的直连字节整体入账（其后不再计）
        val goneIds = live.keys.filterNot { id -> id in present }
        for (id in goneIds) {
            val record = live.remove(id) ?: continue
            bank(record)
        }
        // 现存连接：直连的刷新最新累计值；从 direct 变走别的出站的入账移除（罕见，防错账）
        for (conn in connections) {
            if (isDirect(conn)) {
                live[conn.id] = Record(
                    process = conn.process,
                    uploadBytes = conn.uploadBytes,
                    downloadBytes = conn.downloadBytes,
                    network = conn.network,
                    byDesign = isByDesign(conn),
                )
            } else if (conn.id in live) {
                val record = live.remove(conn.id) ?: continue
                bank(record)
            }
        }
        emit()
    }

    private fun bank(record: Record) {
        val slot = if (record.network == "udp") 2 else 0
        closed[slot] += record.uploadBytes
        closed[slot + 1] += record.downloadBytes
        if (record.byDesign) {
            closed[4] += record.uploadBytes
            closed[5] += record.downloadBytes
        } else if (record.process.isNotEmpty()) {
            closedPerApp[record.process] =
                (closedPerApp[record.process] ?: 0L) + record.uploadBytes + record.downloadBytes
        }
    }

    private fun isDirect(conn: SingBoxConnection): Boolean =
        conn.outbound == ManagedDirectOutboundTag || ManagedDirectOutboundTag in conn.chains

    /**
     * 设计内直连=规则链唯一合法的 UDP 直连来源：QQ/微信白名单包名、NTP(UDP 123)。
     * 「QQ/微信放行」关闭后这两类连接本就不会再落 direct，分类自然为空，无需读开关。
     */
    private fun isByDesign(conn: SingBoxConnection): Boolean =
        conn.network == "udp" &&
            (conn.process in FreeFlowCompiler.UdpWhitelistPackageNames || conn.destinationAddress.endsWith(":123"))

    private fun emit() {
        var tcpUp = closed[0]
        var tcpDown = closed[1]
        var udpUp = closed[2]
        var udpDown = closed[3]
        var designUp = closed[4]
        var designDown = closed[5]
        val perApp = HashMap(closedPerApp)
        for (record in live.values) {
            if (record.network == "udp") {
                udpUp += record.uploadBytes
                udpDown += record.downloadBytes
            } else {
                tcpUp += record.uploadBytes
                tcpDown += record.downloadBytes
            }
            if (record.byDesign) {
                designUp += record.uploadBytes
                designDown += record.downloadBytes
            } else if (record.process.isNotEmpty()) {
                perApp[record.process] =
                    (perApp[record.process] ?: 0L) + record.uploadBytes + record.downloadBytes
            }
        }
        mutableSnapshot.value = DirectTrafficSnapshot(
            uploadBytes = tcpUp + udpUp,
            downloadBytes = tcpDown + udpDown,
            tcpUploadBytes = tcpUp,
            tcpDownloadBytes = tcpDown,
            udpUploadBytes = udpUp,
            udpDownloadBytes = udpDown,
            byDesignUploadBytes = designUp,
            byDesignDownloadBytes = designDown,
            perAppBytes = perApp,
        )
    }
}

/**
 * 轮询壳：代理运行期且观测开关开启时每 5 秒拉一次连接表喂给账本。
 * 开关与清零时间持久化（SharedPreferences）：关闭实时停拉停记账；清零时间供账单对照。
 */
internal class DirectTrafficCounter(
    private val appScope: CoroutineScope,
    private val singBoxRuntime: SingBoxRuntimeRepository,
    private val prefs: SharedPreferences,
) {

    private val ledger = DirectTrafficLedger()
    private var pollJob: Job? = null
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    private val mutableClearedAt = MutableStateFlow(prefs.getLong(KEY_CLEARED_AT, 0L))

    /** 观测开关（持久记忆；UI 与轮询共用）。 */
    val enabled: StateFlow<Boolean> = mutableEnabled

    /** 最近一次清零时间（毫秒；0=从未清零），账单对照用。 */
    val clearedAt: StateFlow<Long> = mutableClearedAt

    val snapshot: StateFlow<DirectTrafficSnapshot> = ledger.snapshot

    fun setEnabled(value: Boolean) {
        mutableEnabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 应用启动后调用一次（幂等）；provider 取当前 AppState（控制端口等）。 */
    fun start(appStateProvider: () -> AppState) {
        if (pollJob?.isActive == true) return
        pollJob = appScope.launch {
            while (isActive) {
                val appState = appStateProvider()
                if (appState.proxyRunning && mutableEnabled.value) {
                    val result = runCatching { singBoxRuntime.getConnections(appState) }
                    val failure = result.exceptionOrNull()
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    result.getOrNull()?.getOrNull()?.let { state -> ledger.onPoll(state.connections) }
                }
                delay(PollIntervalMillis)
            }
        }
    }

    fun reset() {
        ledger.reset()
        val now = System.currentTimeMillis()
        mutableClearedAt.value = now
        prefs.edit().putLong(KEY_CLEARED_AT, now).apply()
    }

    private companion object {
        const val PollIntervalMillis = 5_000L
        const val KEY_ENABLED = "observation_enabled"
        const val KEY_CLEARED_AT = "cleared_at"
    }
}
