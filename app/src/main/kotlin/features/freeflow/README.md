# features/freeflow — 免流功能（全新设计）

设计文档：`_t/asteriskbox-apk/免流重构方案-全新设计.md`（唯一权威）。本包按其落地，替代旧 `features/mtl`（已删除）。

## 结构

```
features/freeflow/
├── domain/                     纯 Kotlin 领域模型（全 JVM 可测，无 Android 依赖）
│   ├── FreeFlowProfile.kt      声明档：通道/UDP 策略/CNS/机场/分流/护栏（Room 单行 JSON）
│   ├── Handshake.kt            握手形态 + 真值表唯一门（illegalReason）+ CONNECT 报文构造
│   ├── Family.kt               FreeFlowFamily 密封接口 + 四族注册表（T5/裸/王卡/彩信）
│   ├── GatewayCatalog.kt       族内置节点库 + 伪装素材常量 + 内置认领
│   └── GatewayProfile.kt       网关能力档案（5 组探测）+ verdict + 建议纯函数
├── compile/
│   └── FreeFlowCompiler.kt     纯函数编译器：档 → sing-box 产物（组/出站/路由/DNS）
│                               + 领域铁律自检（违反即编译失败不落盘）
│                               + AppState 区间管理（ownerKey 识别，含旧 mtl_ 遗留清理）
├── migrate/
│   └── FreeFlowMigrator.kt     旧 features/mtl 状态 → 声明档 一次性迁移（唯一允许 JSON 嗅探处）
├── store/
│   └── FreeFlowStores.kt       Room 档仓库 + prefs 五店（bypass/混淆Host/探测档案/拒绝统计）
│                               （prefs 文件名沿用旧版 mtl_*，用户数据无缝继承）
├── probe/
│   └── AndroidGatewayProber.kt 5 组最小 CONNECT 探针（绑物理网卡，纯诊断）
├── usecase/
│   └── FreeFlowUseCase.kt      事务化全操作：改档 → 编译(自检) → validate → CAS 提交
│                               →（运行中由 App.kt commit lambda 热重载引擎）
└── ui/
    └── FreeFlowPage.kt         四区块页面：状态 / 通道 / 策略 / 诊断（Route.Mianliu 复用）
```

## 数据层改动（集成点）

- `OutboundGroupState.ownerKey` / `OutboundState.meta` 新字段（结构化身份，替代组名/前缀/UA 嗅探）
- Room v3→v4：`outbounds.meta` + `outbound_groups.ownerKey` 列 + `free_flow_profile` 表（备份模型同步，向后兼容）
- AppState 里的免流产物 = 编译缓存（只读投影）；airport 组成员为透传货（订阅拉取不丢）

## 不变量（编译期断言，违反即失败）

QUIC 80+443 双端口拒 / IPv6 三层收口 / UDP 白名单外拒（AllowAll 时 allow 在 reject 前）/
遥测拦截一致性 / 私网直连禁用 ip_is_private（防误吞 fc00::/18）/ NTP 专项在 FakeIP 入口前 /
FakeIP 服务器在场 / CNS 独立组隔离 / CNS 配置无 udp_flag 串 / 真值表门 / 分流目标存在性。

## 测试

`app/src/test/kotlin/features/freeflow/`：编译器（9）+ 迁移（4）+ 建议纯函数（4），共 17 项，全 JVM。

## 里程碑边界（按设计文档 M1–M3 交付）

- 未含（M4）：通道组 urltest 自动选优（组渲染仅 selector）、令牌轮换 diff、503 统计自动喂入
  （`GatewayRejectStatsStore.record` 已就位，等内核日志聚合接线）。
- 机场本地文件导入走通用出站导入入口（组为 ownerKey 标记，编译透传保留成员）。
