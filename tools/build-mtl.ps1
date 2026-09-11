# 一键 mtl 构建（审计清单 #12）：核版本标记 + 预置目录同步 + 推包 zip + AAR + APK 全链路。
# 用法: powershell -File tools/build-mtl.ps1 -Tag mtl29
param([Parameter(Mandatory = $true)][string]$Tag)
$ErrorActionPreference = 'Stop'
$app = Split-Path -Parent $PSScriptRoot
$t = Split-Path -Parent $app
$kernel = Join-Path $t 'singbox-15atp'
$deliver = Join-Path $t 'asteriskbox-apk'
$toolchain = Join-Path (Split-Path -Parent $t) '_toolchain'
$env:GOPROXY = 'https://goproxy.cn,direct'
$env:PATH = (Join-Path $toolchain 'go\bin') + ";$env:USERPROFILE\go\bin;" + (Join-Path $toolchain 'jdk\jdk-17.0.2\bin') + ";$env:PATH"
$env:ANDROID_HOME = Join-Path $toolchain 'android-sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\29.0.14206865'
$tags = 'with_ebpf,with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme,with_clash_api,with_tailscale,with_ccm,with_ocm,with_cloudflared,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0'

Push-Location $kernel
# 1. 独立核（带版本标记）→ 预置目录 + 推包 zip（漏拷预置目录=ROOT 模式跑旧核的历史事故不再犯）
$env:JAVA_HOME = Join-Path $toolchain 'jdk\jdk-17.0.2'
$env:CGO_ENABLED = '0'; $env:GOOS = 'android'; $env:GOARCH = 'arm64'
go build -trimpath -tags $tags -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.15.0-alpha.2-reF1nd-$Tag -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" -o "out/sing-box-$Tag-arm64" ./cmd/sing-box
if ($LASTEXITCODE -ne 0) { throw 'core build failed' }
Copy-Item "out/sing-box-$Tag-arm64" (Join-Path $app 'app\singbox-core-prebuilt\arm64-v8a\libsing-box.so') -Force
$stage = Join-Path $kernel 'out\stage'
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item "out/sing-box-$Tag-arm64" (Join-Path $stage 'sing-box') -Force
Compress-Archive -Path (Join-Path $stage 'sing-box') -DestinationPath (Join-Path $deliver "sing-box-core-$Tag-arm64.zip") -Force

# 2. libbox AAR（全 ABI）→ app/libs
$env:CGO_ENABLED = ''; $env:GOOS = ''; $env:GOARCH = ''
go run ./cmd/internal/build_libbox -target android
if ($LASTEXITCODE -ne 0) { throw 'aar build failed' }
Copy-Item libbox.aar (Join-Path $app 'app\libs\libbox.aar') -Force
Pop-Location

# 3. APK → 交付目录
Push-Location $app
$env:JAVA_HOME = Join-Path $toolchain 'jdk\jdk-26.0.2.1+1'
./gradlew assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'apk build failed' }
$out = 'app\build\outputs\apk\debug'
Copy-Item "$out\app-arm64-v8a-debug.apk" (Join-Path $deliver "AsteriskBOX-1.1.7-dev-$Tag-arm64.apk") -Force
Copy-Item "$out\app-universal-debug.apk" (Join-Path $deliver "AsteriskBOX-1.1.7-dev-$Tag-universal.apk") -Force
Pop-Location
Write-Host "BUILD $Tag DONE: APK + core zip in $deliver"
