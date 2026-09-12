<#
.SYNOPSIS
    Fetch the embedded Android JRE runtime archives into app assets.

.DESCRIPTION
    The JRE is NOT stored in git (36MB per ABI). This script downloads the
    upstream PojavLauncher Android OpenJDK 17 archives into
    app\src\main\assets\jre\ so Gradle can package them into the APK.

    arm64-v8a is the shipped default; pass -IncludeX86_64 to also embed the
    x86_64 build (used by the PC emulator).

.EXAMPLE
    .\fetch-jre-assets.ps1
    .\fetch-jre-assets.ps1 -IncludeX86_64
#>
[CmdletBinding()]
param(
    [switch]$IncludeX86_64,
    [string]$Proxy = "http://127.0.0.1:7897",
    [switch]$NoProxy
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Dest = Join-Path $Root "app\src\main\assets\jre"
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
