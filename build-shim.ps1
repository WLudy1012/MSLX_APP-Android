<#
.SYNOPSIS
    Rebuild the heap-tagging shim (libmslxnotag.so) shipped in app assets.

.DESCRIPTION
    源文件：app\src\main\cpp\shim\mslxnotag.c（用途与约束见该文件头注释）。
    产物：app\src\main\assets\mslx\libmslxnotag-<abi>.so —— 随 APK 打包，运行时由
    ShizukuController.ensureShimStaged 推到 /data/local/tmp/mslx/lib 后作 LD_PRELOAD 用。

    编译到临时目录并校验（ELF 架构 / 引用 mallopt / 未导出 Agent_OnLoad）后才覆盖，
    避免把坏垫片提交进仓库。NDK 路径取自 local.properties 的 sdk.dir。

.EXAMPLE
    .\build-shim.ps1
    .\build-shim.ps1 -DryRun     # 只编译校验，不覆盖 assets
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$NdkVersion = "28.2.13676358"   # 与 app/build.gradle.kts 的 ndkVersion 保持一致
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Src = Join-Path $Root "app\src\main\cpp\shim\mslxnotag.c"
$Dest = Join-Path $Root "app\src\main\assets\mslx"

# local.properties 是 java properties 格式：路径被转义成 C\:\\Users\\...，需先反转义
$line = Get-Content (Join-Path $Root "local.properties") |
    Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
    Select-Object -First 1
if (-not $line) { throw "local.properties 里没有 sdk.dir" }
$sdkDir = $line.Substring($line.IndexOf('=') + 1).Trim()
$sdkDir = $sdkDir -replace '\\([:=])', '$1' -replace '\\\\', '\'
if (-not (Test-Path $sdkDir)) { throw "sdk.dir 指向的目录不存在：$sdkDir" }

$toolchain = Join-Path $sdkDir "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $toolchain)) { throw "找不到 NDK 工具链：$toolchain（ndkVersion=$NdkVersion）" }

$Abis = @(
    @{ name = "arm64-v8a"; cc = "aarch64-linux-android26-clang.cmd"; machine = 183 },
    @{ name = "x86_64";    cc = "x86_64-linux-android26-clang.cmd";  machine = 62 }
)

$tmp = Join-Path $Root "build\shim"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

foreach ($abi in $Abis) {
    $cc = Join-Path $toolchain $abi.cc
    if (-not (Test-Path $cc)) { throw "找不到编译器：$cc" }
    $out = Join-Path $tmp ("libmslxnotag-" + $abi.name + ".so")
    # -ffunction-sections/-Wl,--gc-sections/-s：产物越小，LD_PRELOAD 注入面越小
    # （带逗号的参数必须整体加引号，否则 PowerShell 把逗号当数组运算符）
    & $cc -shared -fPIC -Os -ffunction-sections -fdata-sections -fvisibility=hidden `
        '-Wl,--gc-sections' '-Wl,-s' -o $out $Src
    if ($LASTEXITCODE -ne 0) { throw "编译失败：$($abi.name)（exit=$LASTEXITCODE）" }

    $bytes = [System.IO.File]::ReadAllBytes($out)
    $ascii = [System.Text.Encoding]::ASCII.GetString($bytes) -replace '[^\x20-\x7e]', ' '
    $machine = [BitConverter]::ToUInt16($bytes, 18)
    if ($bytes[0..3] -join ',' -ne '127,69,76,70') { throw "$($abi.name)：不是 ELF" }
    if ($machine -ne $abi.machine) { throw "$($abi.name)：ELF machine=$machine 期望 $($abi.machine)" }
    if ($ascii -notmatch 'mallopt') { throw "$($abi.name)：未引用 mallopt，垫片不会生效" }
    if ($ascii -match 'Agent_OnLoad') { throw "$($abi.name)：导出了 Agent_OnLoad，会抢走 libinstrument 的实现" }
    Write-Host ("[ok  ] {0}  {1}B  machine={2}" -f $abi.name, $bytes.Length, $machine) -ForegroundColor Green

    if ($DryRun) { continue }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Copy-Item $out (Join-Path $Dest (Split-Path $out -Leaf)) -Force
}

if ($DryRun) { Write-Host "dry-run 完成（未写入 assets）" -ForegroundColor DarkGray }
else { Write-Host "shim 已更新：$Dest" -ForegroundColor Green }
