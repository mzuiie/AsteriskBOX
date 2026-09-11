# 开源仓同步（审计清单 #12）：净身快照强推 mzuiie/AsteriskBOX 与 mzuiie/AsteriskBOX-sing-box。
# 用法: powershell -File tools/sync-github.ps1 -Message "发版说明"
param([string]$Message = '开源快照同步')
$ErrorActionPreference = 'Stop'
$app = Split-Path -Parent $PSScriptRoot
$t = Split-Path -Parent $app
$kernel = Join-Path $t 'singbox-15atp'
$tmp = Join-Path $env:TEMP ('mtlpub-' + [guid]::NewGuid().ToString('n'))

function Publish-Repo($src, $repo, $msg, $scrub) {
    $work = Join-Path $tmp ($repo -replace '/', '-')
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    # git archive 先落文件再解包：PS 管道会把二进制流转码损坏（GNU tar malformed header 事故）
    $archive = Join-Path $tmp (($repo -replace '/', '-') + '.tar')
    Push-Location $src
    git archive HEAD --format=tar -o $archive
    Pop-Location
    & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $work
    Remove-Item $archive
    if ($scrub) { node (Join-Path $PSScriptRoot 'scrub-for-public.js') $work }
    Remove-Item -Recurse -Force (Join-Path $work '.github') -ErrorAction SilentlyContinue
    Push-Location $work
    git init -b main -q
    git add -A
    git commit -q -m $msg
    git remote add origin "https://github.com/$repo.git"
    git -c http.proxy=http://127.0.0.1:7890 push -q --force origin main
    $pushed = git -c http.proxy=http://127.0.0.1:7890 ls-remote origin main
    Pop-Location
    Write-Host "published $repo -> $pushed"
}

New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Publish-Repo $app 'mzuiie/AsteriskBOX' $Message $true
Publish-Repo $kernel 'mzuiie/AsteriskBOX-sing-box' $Message $false
Remove-Item -Recurse -Force $tmp
