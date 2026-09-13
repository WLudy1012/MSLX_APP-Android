<#
.SYNOPSIS
    MSLX-Android local signed release packer (two variants in one run).

.DESCRIPTION
    Builds BOTH release variants with the local keystore and archives them:
      * full  - app-release.apk with the embedded Android JRE runtime (offline local hosting)
      * lite  - app-release-lite.apk without the runtime (small download, local hosting
                downloads the ~36MB runtime on first use)
    Each artifact is verified with apksigner, copied to build\dist with a versioned
    name and accompanied by a .sha256 file.

.EXAMPLE
    .\pack-release.ps1                       # build current versionName (from gradle)
    .\pack-release.ps1 -VersionName 1.6.3    # override versionName via -PversionName
    .\pack-release.ps1 -Only full            # build a single variant

.NOTES
    versionCode is NOT changed by this script - bump it in app/build.gradle.kts
    before an official release. Keystore comes from keystore.properties
    (gitignored); without it the release build is unsigned and apksigner fails.
#>
[CmdletBinding()]
param(
    [string]$VersionName = "",
    [string]$JdkHome = "",
    [ValidateSet("both", "full", "lite")]
    [string]$Only = "both",
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
    if ($line) { $SdkDir = ($line -replace '^sdk\.dir=', '' -replace '\\:', ':') }
}
if (-not $SdkDir -or -not (Test-Path $SdkDir)) { $SdkDir = $env:LOCALAPPDATA + "\Android\Sdk" }
$BuildTools = Join-Path $SdkDir "build-tools"
$Aapt = $null; $ApkSigner = $null
if (Test-Path $BuildTools) {
    $latest = Get-ChildItem $BuildTools -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if ($latest) {
        $Aapt = Get-ChildItem (Join-Path $latest.FullName "aapt*.exe") | Where-Object { $_.Name -eq 'aapt.exe' } | Select-Object -First 1
        $ApkSigner = Join-Path $latest.FullName "apksigner.bat"
        if (-not (Test-Path $ApkSigner)) { $ApkSigner = $null }
        if (-not $Aapt) { $Aapt = Get-ChildItem (Join-Path $latest.FullName "aapt*.exe") | Select-Object -First 1 }
    }
}

# ---------- Embedded JRE assets (本机开服需要) ----------
$JreAssetDir = Join-Path $Root "app\src\jreRuntime\assets\jre"
$HasJre = (Test-Path $JreAssetDir) -and (Get-ChildItem $JreAssetDir -Filter *.tar.xz -ErrorAction SilentlyContinue).Count -gt 0
if (-not $HasJre -and $Only -ne "lite") {
    Write-Host "[1.5/5] JRE assets missing, fetching ..." -ForegroundColor Yellow
    & (Join-Path $Root "fetch-jre-assets.ps1")
    if ($LASTEXITCODE -ne 0) { Write-Warning "fetch-jre-assets.ps1 failed - building without embedded JRE" }
}

# ---------- Gradle ----------
$Gradlew = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradlew)) { throw "gradlew.bat not found in $Root" }

function Build-Variant {
    param([bool]$WithoutJre)
    $label = if ($WithoutJre) { "lite (no embedded JRE)" } else { "full (embedded JRE)" }
    Write-Host "[2/5] Building assembleRelease - $label ..." -ForegroundColor Cyan
    $gradleArgs = @(":app:assembleRelease", "--console=plain")
    if ($VersionName) { $gradleArgs += "-PversionName=$VersionName" }
    if ($WithoutJre) { $gradleArgs += "-PwithoutJre=true" }
    # Out-Host：Gradle 输出直接打到控制台，绝不能混进函数返回值（否则调用方拿到的是日志数组）
    & $Gradlew @gradleArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed for $label (exit $LASTEXITCODE)." }
    $apk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { throw "APK not produced: $apk" }
    return $apk
}

function Inspect-Apk {
    param([string]$ApkPath, [bool]$ExpectJre)
    $entries = tar -tf $ApkPath 2>$null
    $jreEntry = $entries | Where-Object { $_ -like "assets/jre/*.tar.xz" }
    $soEntry = $entries | Where-Object { $_ -like "lib/*/libmslxvm.so" }
    if ($ExpectJre) {
        if ($jreEntry) { Write-Host "      embedded JRE : $($jreEntry -join ', ')" -ForegroundColor DarkGray }
        else { Write-Warning "APK 内没有 assets/jre/*.tar.xz：完整版缺内嵌运行时！" }
    } elseif ($jreEntry) {
        Write-Warning "精简版里出现了内嵌 JRE：$($jreEntry -join ', ')"
    }
    if ($soEntry) { Write-Host "      native vm    : $($soEntry -join ', ')" -ForegroundColor DarkGray }
    else { Write-Warning "APK 内没有 lib/*/libmslxvm.so：进程内 JVM 不可用" }
}

function Publish-Artifact {
    param([string]$Apk, [string]$Version, [string]$Suffix)
    $dist = Join-Path $Root "build\dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $shortSha = (git -C $Root rev-parse --short HEAD 2>$null)
    $base = "MSLX-Console-v$Version"
    if ($shortSha) { $base += "-$shortSha" }
    $base += "-$Suffix-signed"
    $out = Join-Path $dist "$base.apk"
    Copy-Item $Apk $out -Force
    $hash = (Get-FileHash -Algorithm SHA256 $out).Hash.ToLowerInvariant()
    Set-Content -Path "$out.sha256" -Value "$hash  $($out | Split-Path -Leaf)" -Encoding ascii
    Write-Host "[4/5] Copied to $out" -ForegroundColor Cyan
    return [pscustomobject]@{ Path = $out; Sha256 = $hash; SizeMb = [math]::Round((Get-Item $out).Length / 1MB, 1) }
}

# ---------- Full variant ----------
$results = @()
$effVersion = $VersionName
if ($Only -ne "lite") {
    $fullApk = Build-Variant -WithoutJre $false
    Inspect-Apk -ApkPath $fullApk -ExpectJre $true
    if (-not $effVersion -and $Aapt) {
        $badging = & $Aapt.FullName "dump" "badging" $fullApk 2>$null
        $effVersion = ($badging | Select-String "versionName='([^']*)'" | Select-Object -First 1).Matches[0].Groups[1].Value
    }
    if (-not $effVersion) { $effVersion = "unknown" }
    if ($ApkSigner -and -not $SkipVerify) {
        Write-Host "[3/5] Verifying signature (full) ..." -ForegroundColor Cyan
        & $ApkSigner "verify" "--print-certs" $fullApk
        if ($LASTEXITCODE -ne 0) { Write-Warning "apksigner verify FAILED for full APK." }
    }
    $results += Publish-Artifact -Apk $fullApk -Version $effVersion -Suffix "full"
}

# ---------- Lite variant ----------
if ($Only -ne "full") {
    $liteApk = Build-Variant -WithoutJre $true
    Inspect-Apk -ApkPath $liteApk -ExpectJre $false
    if (-not $effVersion) {
        if ($Aapt) {
            $badging = & $Aapt.FullName "dump" "badging" $liteApk 2>$null
            $effVersion = ($badging | Select-String "versionName='([^']*)'" | Select-Object -First 1).Matches[0].Groups[1].Value
        }
        if (-not $effVersion) { $effVersion = "unknown" }
    }
    if ($ApkSigner -and -not $SkipVerify) {
        Write-Host "[3/5] Verifying signature (lite) ..." -ForegroundColor Cyan
        & $ApkSigner "verify" "--print-certs" $liteApk
        if ($LASTEXITCODE -ne 0) { Write-Warning "apksigner verify FAILED for lite APK." }
    }
    $results += Publish-Artifact -Apk $liteApk -Version $effVersion -Suffix "lite"
}

# ---------- Summary ----------
Write-Host "[5/5] DONE" -ForegroundColor Green
Write-Host "----------------------------------------"
foreach ($r in $results) {
    Write-Host ("APK      : {0}  ({1} MB)" -f $r.Path, $r.SizeMb)
    Write-Host ("SHA-256  : {0}" -f $r.Sha256)
}
Write-Host "Version  : $effVersion  (versionCode from app/build.gradle.kts)"
Write-Host "----------------------------------------"
