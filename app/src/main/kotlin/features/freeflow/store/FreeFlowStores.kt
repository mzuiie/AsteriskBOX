// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.freeflow.store

import android.content.Context
import data.AndroidAppStateStore
import features.freeflow.domain.FreeFlowProfile
import features.freeflow.domain.GatewayProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 免流存储层：声明档走 Room（与 AppState 提交链解耦的单行表），
 * 运行期辅助档案走 SharedPreferences。
 *
 * prefs 文件名沿用旧版（mtl_bypass / mtl_obf_host / mtl_probe），
 * 用户既有数据无缝继承，无需搬运（键内结构按新版读写，旧字段忽略）。
 */
object FreeFlowJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
}

/** 声明档持久化（Room free_flow_profile 单行）。 */
class FreeFlowProfileStore(
    private val stateStore: AndroidAppStateStore,
) {
    suspend fun load(): FreeFlowProfile? =
        stateStore.loadFreeFlowProfilePayload()?.let { payload ->
            runCatching { FreeFlowJson.instance.decodeFromString<FreeFlowProfile>(payload) }
                .getOrNull()
        }

    suspend fun save(profile: FreeFlowProfile): Boolean = runCatching {
        stateStore.saveFreeFlowProfilePayload(
            FreeFlowJson.instance.encodeToString(FreeFlowProfile.serializer(), profile),
        )
    }.getOrDefault(false)

    suspend fun clear(): Boolean = stateStore.clearFreeFlowProfile()
}

/** 机场组混淆 Host（按组名存）：编译注入组内成员 Host，订阅拉取后编译期自动重注入。 */
class FreeFlowObfHostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mtl_obf_host", Context.MODE_PRIVATE)

    fun get(name: String): String = prefs.getString(name, "").orEmpty()

    fun set(name: String, host: String) {
        prefs.edit().putString(name, host.trim()).apply()
    }

    fun all(): Map<String, String> = prefs.all
        .mapNotNull { (name, value) -> (value as? String)?.takeIf { it.isNotBlank() }?.let { name to it } }
        .toMap()
}

/** 网关能力档案（按网关 IP 缓存，7 天过期 + 手动重探）。 */
class FreeFlowProbeArchiveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mtl_probe", Context.MODE_PRIVATE)

    fun get(server: String): GatewayProfile? = prefs.getString(key(server), null)?.let { raw ->
        runCatching { FreeFlowJson.instance.decodeFromString<GatewayProfile>(raw) }.getOrNull()
    }

    fun put(profile: GatewayProfile) {
        prefs.edit()
            .putString(key(profile.server), FreeFlowJson.instance.encodeToString(GatewayProfile.serializer(), profile))
            .apply()
    }

    fun all(): List<GatewayProfile> = prefs.all
        .filterKeys { name -> name.startsWith("gw_") }
        .values
        .mapNotNull { value -> (value as? String)?.let { raw -> runCatching { FreeFlowJson.instance.decodeFromString<GatewayProfile>(raw) }.getOrNull() } }

    private fun key(server: String) = "gw_$server"
}

/** 网关拒绝（503/403）目标域统计（N2）：内核日志采样聚合的落点，供"加入 bypass"建议条。 */
class GatewayRejectStatsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ff_reject_stats", Context.MODE_PRIVATE)
    private val key = "suffixes"

    /** 记录一次网关拒绝（调用方从内核日志/连接记录提取目标域名后缀后调用）。 */
    fun record(suffix: String) {
        val cleaned = suffix.trim().lowercase().removePrefix(".")
        if (cleaned.isBlank()) return
        val counts = top(Int.MAX_VALUE).toMutableMap()
        counts[cleaned] = (counts[cleaned] ?: 0) + 1
        prefs.edit()
            .putString(
                key,
                Json.encodeToString(
                    counts.entries.take(64).map { (name, count) -> JsonPrimitive("$name\t$count") },
                ),
            )
            .apply()
    }

    /** 高频被拒域（suffix → 次数，降序）。 */
    fun top(n: Int = 8): Map<String, Int> = prefs.getString(key, null)?.let { raw ->
        runCatching {
            Json.parseToJsonElement(raw).jsonArray
                .mapNotNull { item -> item.jsonPrimitive.content.split('\t').takeIf { it.size == 2 } }
                .mapNotNull { parts -> parts[1].toIntOrNull()?.let { count -> parts[0] to count } }
                .toMap()
        }.getOrNull()
    }.orEmpty()
        .toList()
        .sortedByDescending { it.second }
        .take(n)
        .toMap()

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
