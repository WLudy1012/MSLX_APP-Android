<#
.SYNOPSIS
    Fetch the Android JRE runtime archives used by 本机开服.

.DESCRIPTION
    各归档的角色不同：

    * jre17（内嵌）——不入 git（~36MB/ABI），本脚本下载到
      app\src\jreRuntime\assets\jre\，由 Gradle 打进完整版 APK（-PwithoutJre=true 时不打）。
    * jre21 / jre25（仅镜像分发）——**不进 APK 也不入 git**，用 -Jre21 / -Jre25 下到 build\，
      供 CI（.cnb.yml / .github/workflows/release.yml）作为 Release 附件挂载；
      客户端按「CNB → GitHub → FCL 直链」顺序下载（见 LocalJreManager.JRE21 / JRE25）。
    * jre8（仅镜像分发，可选）——取自 ZalithLauncher 2 内置运行时（universal + bin-<abi>），
      用 -Jre8 下到 build\jre8-mirror；不上传镜像也能用（客户端回退 Zalith jsDelivr/raw 源）。
      上传 Release/CNB 时文件名换成 JRE8 里约定的 jre8-universal.tar.xz / jre8-<abi>-bin.tar.xz。

    arm64-v8a is the shipped default; pass -IncludeX86_64 to also embed the
    x86_64 build (used by the PC emulator).

.EXAMPLE
    .\fetch-jre-assets.ps1
    .\fetch-jre-assets.ps1 -IncludeX86_64
    .\fetch-jre-assets.ps1 -Jre21        # 只拿 jre21 到 build\（发布镜像用）
    .\fetch-jre-assets.ps1 -Jre25        # 只拿 jre25 到 build\（发布镜像用）
    .\fetch-jre-assets.ps1 -Jre8         # 只拿 jre8 双归档到 build\jre8-mirror\（可选镜像）
#>
[CmdletBinding()]
param(
    [switch]$IncludeX86_64,
    [switch]$Jre21,
    [switch]$Jre25,
    [switch]$Jre8,
    [string]$Proxy = "http://127.0.0.1:7897",
    [switch]$NoProxy
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Dest = Join-Path $Root "app\src\jreRuntime\assets\jre"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

$Base = "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559"
$Assets = @(
    @{ name = "jre17-arm64-20210825-release.tar.xz";  sha256 = "c64583ac2e0ec8857e43456fa9adcf482c6a8e454a7133173bf15692d2478b8d" }
)
if ($IncludeX86_64) {
    $Assets += @{ name = "jre17-x86_64-20210825-release.tar.xz"; sha256 = "ebbdf75ab864a83671a108032c30e67174f79cc19596cfc1d7bfb71be26b6e71" }
}

$req = @{ UseBasicParsing = $true; Headers = @{ 'User-Agent' = 'mslx-android-build' }; TimeoutSec = 600 }
if (-not $NoProxy -and $Proxy) { $req.Proxy = $Proxy }

# jre21：仅作为 Release 附件，下到 build\（不进 assets、不入 git）。
if ($Jre21) {
    $buildDir = Join-Path $Root "build"
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $n21 = "jre21-arm64-20260223-release.tar.xz"
    $t21 = Join-Path $buildDir $n21
    $s21 = "d055fc953771e6cfd2206ef770ca675d69b5e4cfd9f3f1e6820ba5820c3cc1dc"
    if (Test-Path $t21) {
        $have = (Get-FileHash -Algorithm SHA256 $t21).Hash.ToLowerInvariant()
        if ($have -eq $s21) { Write-Host "[skip] $n21 already present and verified" -ForegroundColor DarkGray; return }
        Remove-Item $t21 -Force
    }
    Write-Host "[get ] $n21 ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "https://pan.huang1111.cn/f/eWkQI1/$n21" -OutFile $t21 @req
    $got = (Get-FileHash -Algorithm SHA256 $t21).Hash.ToLowerInvariant()
    if ($got -ne $s21) { Remove-Item $t21 -Force; throw "SHA-256 mismatch for ${n21}: got $got" }
    Write-Host "       ok  $([math]::Round((Get-Item $t21).Length/1MB,1))MB  $got" -ForegroundColor Green
    Write-Host "jre21 ready: $t21（只用于 Release 附件，勿放进 assets）" -ForegroundColor Green
    return
}

# jre25：同 jre21 模式，仅作为 Release 附件（FCL 下载站直链）。
if ($Jre25) {
    $buildDir = Join-Path $Root "build"
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $n25 = "jre25-arm64-20260223-release.tar.xz"
    $t25 = Join-Path $buildDir $n25
    $s25 = "0fdf6d19fe66ea61c12caa24bd655227ddb0d7d9c16c0f13281a7c2878635286"
    if (Test-Path $t25) {
        $have = (Get-FileHash -Algorithm SHA256 $t25).Hash.ToLowerInvariant()
        if ($have -eq $s25) { Write-Host "[skip] $n25 already present and verified" -ForegroundColor DarkGray; return }
        Remove-Item $t25 -Force
    }
    Write-Host "[get ] $n25 ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "https://pan.huang1111.cn/f/5NOltm/$n25" -OutFile $t25 @req
    $got = (Get-FileHash -Algorithm SHA256 $t25).Hash.ToLowerInvariant()
    if ($got -ne $s25) { Remove-Item $t25 -Force; throw "SHA-256 mismatch for ${n25}: got $got" }
    Write-Host "       ok  $([math]::Round((Get-Item $t25).Length/1MB,1))MB  $got" -ForegroundColor Green
    Write-Host "jre25 ready: $t25（只用于 Release 附件，勿放进 assets）" -ForegroundColor Green
    return
}

# jre8：ZalithLauncher 2 内置运行时（OpenJDK 8u442 bionic 构建，GPL 系列许可）。
# 客户端对 jre8 有 Zalith 直连兜底，镜像上传是可选项；上传附件名必须与下面 name 一致。
if ($Jre8) {
    $buildDir = Join-Path $Root "build\jre8-mirror"
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $zalith = "https://cdn.jsdelivr.net/gh/ZalithLauncher/ZalithLauncher2@main/ZalithLauncher/src/main/assets/runtimes/jre-8"
    $files = @(
        @{ src = "$zalith/universal.tar.xz";  name = "jre8-universal.tar.xz";   sha256 = "150072cfa1d9e037c31e4bf9770aa5470ae46d0bfefd366b7ce5b2f940ede3f3" },
        @{ src = "$zalith/bin-arm64.tar.xz";  name = "jre8-arm64-bin.tar.xz";   sha256 = "deed9083a1047af1afaf2d7f1a2de4ae39fadf62c52881f075793e80274956cf" },
        @{ src = "$zalith/bin-x86_64.tar.xz"; name = "jre8-x86_64-bin.tar.xz";  sha256 = "37c3f7214ce3086575d7210baa25aee130d8db28c57739f598d24dfbfff32612" }
    )
    foreach ($a in $files) {
        $target = Join-Path $buildDir $a.name
        if (Test-Path $target) {
            $have = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
            if ($have -eq $a.sha256) { Write-Host "[skip] $($a.name) already present and verified" -ForegroundColor DarkGray; continue }
            Remove-Item $target -Force
        }
        Write-Host "[get ] $($a.name) ..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $a.src -OutFile $target @req
        $got = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
        if ($got -ne $a.sha256) { Remove-Item $target -Force; throw "SHA-256 mismatch for $($a.name): got $got" }
        Write-Host "       ok  $([math]::Round((Get-Item $target).Length/1MB,1))MB  $got" -ForegroundColor Green
    }
    Write-Host "jre8 ready: $buildDir（Release/CNB 附件名与上述 name 一致，勿放进 assets）" -ForegroundColor Green
    return
}

foreach ($a in $Assets) {
    $target = Join-Path $Dest $a.name
    if (Test-Path $target) {
        $have = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
        if ($have -eq $a.sha256) { Write-Host "[skip] $($a.name) already present and verified" -ForegroundColor DarkGray; continue }
        Write-Warning "$($a.name) hash mismatch, re-downloading"
        Remove-Item $target -Force
    }
    Write-Host "[get ] $($a.name) ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "$Base/$($a.name)" -OutFile $target @req
    $got = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
    if ($got -ne $a.sha256) {
        Remove-Item $target -Force
        throw "SHA-256 mismatch for $($a.name): got $got expected $($a.sha256)"
    }
    Write-Host "       ok  $([math]::Round((Get-Item $target).Length/1MB,1))MB  $got" -ForegroundColor Green
}
Write-Host "assets ready: $Dest" -ForegroundColor Green
