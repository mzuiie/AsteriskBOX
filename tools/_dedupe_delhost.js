// 一次性迁移脚本：功能去重（删 Del Host 开关，保留「裸 CONNECT」族入口）
const fs = require('fs');
const root = 'C:/Users/abare/Documents/zcode/ATP/_t/AsteriskBOX/';
function patch(rel, pairs) {
  const p = root + rel;
  let s = fs.readFileSync(p, 'utf8');
  const miss = [];
  for (const [a, b] of pairs) {
    if (!s.includes(a)) { miss.push(a.slice(0, 50)); continue; }
    s = s.split(a).join(b);
  }
  fs.writeFileSync(p, s);
  console.log(rel.split('/').pop(), miss.length ? 'MISS: ' + miss.join(' | ') : 'ok');
}

patch('app/src/main/kotlin/features/freeflow/compile/FreeFlowCompiler.kt', [
  [
    '        val delHost = profile.channel.delHost && profile.channel.familyKey == BaiduT5Family.key\n' +
    '        val channelNodes = profile.channel.nodes.ifEmpty {\n' +
    '            freeFlowBuiltinNodes(profile.channel.familyKey, profile.channel.delHost)\n' +
    '        }',
    '        val channelNodes = profile.channel.nodes.ifEmpty {\n' +
    '            freeFlowBuiltinNodes(profile.channel.familyKey)\n' +
    '        }',
  ],
  [
    '            val json = node.handshake.let { handshake ->\n' +
    '                if (delHost && !handshake.delHost) {\n' +
    '                    // Del Host 开关：百度族整组换裸握手（T5 令牌网关收裸请求必 403）\n' +
    '                    buildChannelOutboundJson(handshake.copy(delHost = true, headers = emptyList()), node.server, node.serverPort)\n' +
    '                } else {\n' +
    '                    buildChannelOutboundJson(handshake, node.server, node.serverPort, wapTag)\n' +
    '                }\n' +
    '            }',
    '            val json = buildChannelOutboundJson(node.handshake, node.server, node.serverPort, wapTag)',
  ],
  ['import features.freeflow.domain.BaiduT5Family\n', ''],
]);

patch('app/src/main/kotlin/features/freeflow/usecase/FreeFlowUseCase.kt', [
  [
    '    /** 一键套用（幂等）：确保档启用并按档全量重建产物。 */\n' +
    '    suspend fun applyTemplate(familyKey: String? = null, delHost: Boolean? = null): Result {\n' +
    '        val profile = currentProfile()\n' +
    '        val channel = profile.channel.copy(\n' +
    '            familyKey = familyKey ?: profile.channel.familyKey,\n' +
    '            delHost = delHost ?: profile.channel.delHost,\n' +
    '        )\n' +
    '        return saveAndCommit(profile.copy(enabled = true, channel = channel))\n' +
    '    }',
    '    /** 一键套用（幂等）：确保档启用并按档全量重建产物。 */\n' +
    '    suspend fun applyTemplate(familyKey: String? = null): Result {\n' +
    '        val profile = currentProfile()\n' +
    '        val channel = profile.channel.copy(familyKey = familyKey ?: profile.channel.familyKey)\n' +
    '        return saveAndCommit(profile.copy(enabled = true, channel = channel))\n' +
    '    }',
  ],
  [
    '    /** Del Host 开关：仅百度族语义，整组换 TPBox 裸握手节点（T5 网关收裸请求必 403）。 */\n' +
    '    suspend fun setDelHost(delHost: Boolean): Result {\n' +
    '        val profile = currentProfile()\n' +
    '        return saveAndCommit(\n' +
    '            profile.copy(\n' +
    '                enabled = true,\n' +
    '                channel = profile.channel.copy(delHost = delHost, nodes = emptyList()),\n' +
    '            ),\n' +
    '        )\n' +
    '    }\n\n',
    '',
  ],
  [
    '            ?: features.freeflow.domain.freeFlowBuiltinNodes(\n' +
    '                profile.channel.familyKey,\n' +
    '                profile.channel.delHost,\n' +
    '            ).firstOrNull()',
    '            ?: features.freeflow.domain.freeFlowBuiltinNodes(profile.channel.familyKey).firstOrNull()',
  ],
]);

patch('app/src/main/kotlin/features/freeflow/migrate/FreeFlowMigrator.kt', [
  [
    '        // 族识别（旧版嗅探语义：彩信 detour → del_host → 默认百度 T5）\n' +
    '        val hasMmsRelay = members.any { outbound -> outbound.json.contains("\\"detour\\"") }\n' +
    '        val hasDelHost = members.any { outbound -> outbound.json.contains("\\"del_host\\"") }\n' +
    '        val familyKey = if (hasMmsRelay) "mms" else "baidu_t5"\n' +
    '        val delHost = hasDelHost && familyKey == "baidu_t5"',
    '        // 族识别（旧版嗅探语义：彩信 detour → 裸 del_host → 默认百度 T5；Del Host 开关已并入裸族）\n' +
    '        val hasMmsRelay = members.any { outbound -> outbound.json.contains("\\"detour\\"") }\n' +
    '        val hasDelHost = members.any { outbound -> outbound.json.contains("\\"del_host\\"") }\n' +
    '        val familyKey = when {\n' +
    '            hasMmsRelay -> "mms"\n' +
    '            hasDelHost -> "tpbox_bare"\n' +
    '            else -> "baidu_t5"\n' +
    '        }',
  ],
  [
    '                familyKey = familyKey,\n                delHost = delHost,\n                nodes = nodes,',
    '                familyKey = familyKey,\n                nodes = nodes,',
  ],
]);

patch('app/src/main/kotlin/features/freeflow/ui/FreeFlowPage.kt', [
  [
    '                ChannelBlock(\n' +
    '                    profile = profile,\n' +
    '                    enabled = working.not(),\n' +
    '                    onFamily = { key -> scope.launch { run { useCase.setChannelFamily(key) } } },\n' +
    '                    onDelHost = { value -> scope.launch { run { useCase.setDelHost(value) } } },\n' +
    '                    onSubscription = { url -> scope.launch { run { useCase.setChannelSubscription(url) } } },\n' +
    '                )',
    '                ChannelBlock(\n' +
    '                    profile = profile,\n' +
    '                    enabled = working.not(),\n' +
    '                    onFamily = { key -> scope.launch { run { useCase.setChannelFamily(key) } } },\n' +
    '                    onSubscription = { url -> scope.launch { run { useCase.setChannelSubscription(url) } } },\n' +
    '                )',
  ],
  [
    'private fun ChannelBlock(\n    profile: FreeFlowProfile?,\n    enabled: Boolean,\n    onFamily: (String) -> Unit,\n    onDelHost: (Boolean) -> Unit,\n    onSubscription: (String) -> Unit,\n) {',
    'private fun ChannelBlock(\n    profile: FreeFlowProfile?,\n    enabled: Boolean,\n    onFamily: (String) -> Unit,\n    onSubscription: (String) -> Unit,\n) {',
  ],
  [
    '            if (profile?.channel?.familyKey == features.freeflow.domain.BaiduT5Family.key) {\n' +
    '                Row(verticalAlignment = Alignment.CenterVertically) {\n' +
    '                    Column(modifier = Modifier.weight(1f)) {\n' +
    '                        Text(stringResource(R.string.ff_del_host))\n' +
    '                        Text(\n' +
    '                            stringResource(R.string.ff_del_host_hint),\n' +
    '                            style = MaterialTheme.typography.bodySmall,\n' +
    '                            color = MaterialTheme.colorScheme.onSurfaceVariant,\n' +
    '                        )\n' +
    '                    }\n' +
    '                    Switch(checked = profile?.channel?.delHost == true, onCheckedChange = onDelHost, enabled = enabled)\n' +
    '                }\n' +
    '            }\n',
    '',
  ],
  [
    '                val nodeCount = profile.channel.nodes.ifEmpty {\n' +
    '                    features.freeflow.domain.freeFlowBuiltinNodes(profile.channel.familyKey, profile.channel.delHost)\n' +
    '                }.size',
    '                val nodeCount = profile.channel.nodes.ifEmpty {\n' +
    '                    features.freeflow.domain.freeFlowBuiltinNodes(profile.channel.familyKey)\n' +
    '                }.size',
  ],
]);

patch('app/src/test/kotlin/features/freeflow/FreeFlowCompilerTest.kt', [
  [
    '    @Test\n' +
    '    fun delHostSwapsChannelToBareNodes() {\n' +
    '        val artifacts = (compile(defaultProfile { profile ->\n' +
    '            profile.copy(channel = profile.channel.copy(delHost = true))\n' +
    '        }) as FreeFlowCompiler.Result.Success).artifacts',
    '    @Test\n' +
    '    fun bareFamilyUsesBareNodes() {\n' +
    '        val artifacts = (compile(defaultProfile { profile ->\n' +
    '            profile.copy(channel = profile.channel.copy(familyKey = "tpbox_bare"))\n' +
    '        }) as FreeFlowCompiler.Result.Success).artifacts',
  ],
]);

// strings: 删除 ff_del_host 两键（双语言，含此前改名后的新文案）
for (const f of ['app/src/main/res/values/strings.xml', 'app/src/main/res/values-zh-rCN/strings.xml']) {
  let s = fs.readFileSync(root + f, 'utf8');
  s = s.replace(/ *\n *<string name="ff_del_host">[^<]*<\/string>/g, '');
  s = s.replace(/ *\n *<string name="ff_del_host_hint">[^<]*<\/string>/g, '');
  fs.writeFileSync(root + f, s);
  console.log(f.split('/').pop(), 'del_host keys removed:', !s.includes('ff_del_host'));
}
