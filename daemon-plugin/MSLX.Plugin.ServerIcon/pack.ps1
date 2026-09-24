# Build and package the MSLX server-icon plugin into an installable single-file DLL.
# Usage: powershell -ExecutionPolicy Bypass -File pack.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = Join-Path $root 'MSLX.Plugin.ServerIcon.csproj'
$dist = Join-Path $root 'dist'

Write-Host '[1/3] Building plugin (Release)...'
dotnet build $proj -c Release --nologo
if ($LASTEXITCODE -ne 0) { throw 'dotnet build failed' }

$bin = Join-Path $root 'bin\Release\net10.0'
$dll = Join-Path $bin 'MSLX.Plugin.ServerIcon.dll'
$pdb = Join-Path $bin 'MSLX.Plugin.ServerIcon.pdb'
if (-not (Test-Path $dll)) { throw "dll not found: $dll" }

Write-Host '[2/3] Collecting artifacts...'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item $dll (Join-Path $dist 'MSLX.Plugin.ServerIcon.dll') -Force
if (Test-Path $pdb) { Copy-Item $pdb (Join-Path $dist 'MSLX.Plugin.ServerIcon.pdb') -Force }
Copy-Item (Join-Path $root 'README.md') (Join-Path $dist 'README.md') -Force

Write-Host '[3/3] Creating zip package...'
$zip = Join-Path $dist 'MSLX.Plugin.ServerIcon.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $dist 'MSLX.Plugin.ServerIcon.dll'), (Join-Path $dist 'README.md') -DestinationPath $zip

Write-Host ''
Write-Host 'Done. Install artifacts:'
Write-Host "  dll : $(Join-Path $dist 'MSLX.Plugin.ServerIcon.dll')"
Write-Host "  zip : $zip"
Write-Host ''
Write-Host 'Install: copy the dll into <Daemon AppData>/Plugins/ and restart the daemon,'
Write-Host 'or use the plugin manager download/install API with a public URL to the dll.'
