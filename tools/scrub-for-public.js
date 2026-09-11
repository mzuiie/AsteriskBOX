// 开源快照脱敏（随 freeflow 重构更新）：机场订阅快照/个人订阅地址 → 占位符。
// 用法: node tools/scrub-for-public.js <快照目录>
const fs = require('fs');
const path = require('path');
const dir = process.argv[2];
if (!dir) { console.error('usage: node scrub-for-public.js <dir>'); process.exit(1); }

// 机场订阅快照 → 占位符（内置快照不含真实订阅凭据）
const snap = `# 内置快照示例（开源仓库不含真实订阅凭据）
# 在免流页「内置快照」导入前, 请替换为你的机场订阅内容（Clash YAML proxies 格式）
port: 7890
socks-port: 7891
proxies:
  - { name: '示例节点-香港', type: ss, server: example.com, port: 8443, cipher: aes-128-gcm, password: your-airport-password, udp: true }
  - { name: '示例节点-日本', type: ss, server: example.org, port: 8443, cipher: aes-128-gcm, password: your-airport-password, udp: true }
proxy-groups:
  - { name: PROXY, type: select, proxies: ['示例节点-香港', '示例节点-日本'] }
rules:
  - MATCH,PROXY
`;
const snapPath = dir + '/app/src/main/assets/mtl/airport-snapshot.yaml';
if (fs.existsSync(snapPath)) fs.writeFileSync(snapPath, snap);

// 个人网关订阅地址（freeflow 域常量）→ 空值
const catalog = dir + '/app/src/main/kotlin/features/freeflow/domain/GatewayCatalog.kt';
if (fs.existsSync(catalog)) {
    let t = fs.readFileSync(catalog, 'utf8');
    t = t.replace(
        /const val FreeFlowDefaultSubscriptionUrl = "[^"]*"/,
        'const val FreeFlowDefaultSubscriptionUrl = "" // 个人网关订阅地址(开源仓库不含), 使用内置节点或在免流页填入自己的订阅',
    );
    fs.writeFileSync(catalog, t);
}

// 全树残留扫描: 任何文件中出现个人订阅/凭据标记即失败
const markers = /cxksl|3e158913|c70e612a|5f1cb5b1|qwer-cn|kpy\.edu/i;
const leftovers = [];
(function scan(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            if (entry.name === '.git' || entry.name === '.github') continue;
            scan(full);
        } else if (entry.isFile()) {
            const hit = fs.readFileSync(full, 'utf8').match(markers);
            if (hit) leftovers.push(full.replace(dir, '') + ' -> ' + hit.join(','));
        }
    }
})(dir);
if (leftovers.length) {
    console.error('SCRUB LEFTOVER:\n' + leftovers.join('\n'));
    process.exit(1);
}
console.log('scrub ok');
