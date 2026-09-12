<#
.SYNOPSIS
    MSLX-Android local signed release packer.
    Builds the release APK with the local keystore, verifies the signature,
    copies the artifact to build\dist with a versioned name and writes a
    .sha256 file. Prints a summary for delivery notes.

.EXAMPLE
    .\pack-release.ps1                       # build current versionName (from gradle)
    .\pack-release.ps1 -VersionName 1.6.2    # override versionName via -PversionName
    .\pack-release.ps1 -JdkHome C:\Path\jbr  # custom JDK

.NOTES
    versionCode is NOT changed by this script - bump it in app/build.gradle.kts
    before an official release. Keystore comes from keystore.properties
    (gitignored); without it the release build is unsigned and apksigner fails.
#>
[CmdletBinding()]
param(
    [string]$VersionName = "",
    [string]$JdkHome = "",
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

# ---------- JDK ----------
if (-not $JdkHome) {
    if ($env:JAVA_HOME) { $JdkHome = $env:JAVA_HOME }
    else {
        $Fallback = "C:\Users\wangs\.jdks\jbr-21.0.11"
        if (Test-Path $Fallback) { $JdkHome = $Fallback }
    }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome "bin\java.exe"))) {
    throw "JDK not found. Pass -JdkHome (expects bin\java.exe inside)."
}
$env:JAVA_HOME = $JdkHome
Write-Host "[1/5] JAVA_HOME = $JdkHome" -ForegroundColor Cyan

# ---------- Android SDK (for aapt/apksigner) ----------
$SdkDir = $null
$LocalProps = Join-Path $Root "local.properties"
if (Test-Path $LocalProps) {
    $line = (Get-Content $LocalProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
    if ($line) {
        $SdkDir = ($line -replace '^sdk\.dir=', '' -replace '\\:', ':')
    }
}
if (-not $SdkDir -or -not (Test-Path $SdkDir)) { $SdkDir = $env:LOCALAPPDATA + "\Android\Sdk" }
$BuildTools = Join-Path $SdkDir "build-tools"
$Aapt = $null; $ApkSigner = $null
if (Test-Path $BuildTools) {
    $latest = Get-ChildItem $BuildTools -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if ($latest) {
        $Aapt = Get-ChildItem (Join-Path $latest.FullName "aapt*.exe") | Where-Object { $_.Name -like 'aapt.exe' -or $_.Name -like 'aapt2.exe' } | Select-Object -First 1
        $ApkSigner = Join-Path $latest.FullName "apksigner.bat"
        if (-not (Test-Path $ApkSigner)) { $ApkSigner = $null }
        if (-not $Aapt) {
            # aapt2 needs a "dump badging" - use aapt.exe if present else any
            $Aapt = Get-ChildItem (Join-Path $latest.FullName "aapt*.exe") | Select-Object -First 1
        }
    }
}

# ---------- Embedded JRE assets (本机开服需要) ----------
$JreAssetDir = Join-Path $Root "app\src\main\assets\jre"
$HasJre = (Test-Path $JreAssetDir) -and (Get-ChildItem $JreAssetDir -Filter *.tar.xz -ErrorAction SilentlyContinue).Count -gt 0
if (-not $HasJre) {
    Write-Host "[1.5/5] JRE assets missing, fetching ..." -ForegroundColor Yellow
    & (Join-Path $Root "fetch-jre-assets.ps1")
    if ($LASTEXITCODE -ne 0) { Write-Warning "fetch-jre-assets.ps1 failed - building without embedded JRE" }
}

# ---------- Gradle assembleRelease ----------
$Gradlew = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradlew)) { throw "gradlew.bat not found in $Root" }
Write-Host "[2/5] Building assembleRelease ..." -ForegroundColor Cyan
$GradleArgs = @(":app:assembleRelease", "--console=plain")
if ($VersionName) { $GradleArgs += "-PversionName=$VersionName" }
& $Gradlew @GradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }

# ---------- Locate APK & read effective versionName ----------
$SrcApk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $SrcApk)) { throw "APK not produced: $SrcApk" }

# APK 内容自检：本机开服依赖内嵌 JRE 与 native 桥接库（用 bsdtar 列 zip 条目，避免 .NET 程序集依赖）
$apkEntries = tar -tf $SrcApk 2>$null
if ($apkEntries) {
    $jreEntry = $apkEntries | Where-Object { $_ -like "assets/jre/*.tar.xz" }
    $soEntry = $apkEntries | Where-Object { $_ -like "lib/*/libmslxvm.so" }
    if ($jreEntry) { Write-Host "      embedded JRE : $($jreEntry -join ', ')" -ForegroundColor DarkGray }
    else { Write-Warning "APK 内没有 assets/jre/*.tar.xz：本机开服将只能走下载路径" }
    if ($soEntry) { Write-Host "      native vm    : $($soEntry -join ', ')" -ForegroundColor DarkGray }
    else { Write-Warning "APK 内没有 lib/*/libmslxvm.so：进程内 JVM 不可用" }
}

$EffVersion = $VersionName
if (-not $EffVersion -and $Aapt) {
    $Badging = & $Aapt.FullName "dump" "badging" $SrcApk 2>$null
    $EffVersion = ($Badging | Select-String "versionName='([^']*)'" | Select-Object -First 1).Matches[0].Groups[1].Value
}
if (-not $EffVersion) { $EffVersion = "unknown" }

# ---------- Verify signature ----------
$Signed = $false
if ($ApkSigner -and -not $SkipVerify) {
    Write-Host "[3/5] Verifying signature ..." -ForegroundColor Cyan
    & $ApkSigner "verify" "--print-certs" $SrcApk
    $Signed = ($LASTEXITCODE -eq 0)
    if (-not $Signed) { Write-Warning "apksigner verify FAILED - APK is NOT signed with the release key." }
} elseif ($SkipVerify) {
    Write-Host "[3/5] Signature verification skipped (-SkipVerify)." -ForegroundColor Yellow
}

# ---------- Copy to build\dist with versioned name + sha256 ----------
$Dist = Join-Path $Root "build\dist"
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
$Sha = (git -C $Root rev-parse --short HEAD 2>$null)
$Base = "MSLX-Console-v$EffVersion"
if ($Sha) { $Base += "-$Sha" }
$Base += "-signed"
$OutApk = Join-Path $Dist "$Base.apk"
Copy-Item $SrcApk $OutApk -Force
$Hash = (Get-FileHash -Algorithm SHA256 $OutApk).Hash.ToLowerInvariant()
Set-Content -Path "$OutApk.sha256" -Value "$Hash  $($OutApk | Split-Path -Leaf)" -Encoding ascii
Write-Host "[4/5] Copied to $OutApk" -ForegroundColor Cyan

# ---------- Summary ----------
Write-Host "[5/5] DONE" -ForegroundColor Green
Write-Host "----------------------------------------"
Write-Host "APK      : $OutApk"
Write-Host "Version  : $EffVersion  (versionCode from app/build.gradle.kts)"
Write-Host "SHA-256  : $Hash"
Write-Host "Signed   : $Signed"
Write-Host "----------------------------------------"
