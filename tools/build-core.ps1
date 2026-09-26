# Builds the pure-Java AMV core (and the desktop self-test).
#
# Windows notes that this script exists to work around:
#  * the workspace path contains non-ASCII characters, so every argument handed to a native
#    tool must be passed as a PowerShell array element (not via javac @argfiles, which are
#    read with a fixed charset and also treat backslashes as escapes);
#  * javac is invoked with -encoding UTF-8 for the sources.
param(
    [switch]$SkipTest
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdkRoot = Join-Path $root '.tools\jdk'
$jdk = (Get-ChildItem $jdkRoot -Directory | Select-Object -First 1).FullName
if (-not $jdk) { throw "JDK not found under $jdkRoot" }
$classes = Join-Path $root '.tools\corebuild\classes'
$testClasses = Join-Path $root '.tools\corebuild\testclasses'
Remove-Item -Recurse -Force $classes, $testClasses -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null

$coreSrc = @(Get-ChildItem (Join-Path $root 'core\src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "core sources: $($coreSrc.Count)"
$javacArgs = @('-encoding', 'UTF-8', '-nowarn', '-d', $classes) + $coreSrc
& (Join-Path $jdk 'bin\javac.exe') $javacArgs
if ($LASTEXITCODE -ne 0) { throw "core compile failed ($LASTEXITCODE)" }

if (-not $SkipTest) {
    # App classes that have no Android dependencies are compiled here too, so their logic can be
    # exercised by the desktop self-test (currently the settings/estimate helper).
    $appDesktop = @()
    $presets = Join-Path $root 'app\src\com\amvconverter\app\Presets.java'
    if (Test-Path $presets) { $appDesktop += $presets }

    $testSrc = @(Get-ChildItem (Join-Path $root 'core\test') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $testSrc += $appDesktop
    Write-Host "test sources: $($testSrc.Count)"
    $tArgs = @('-encoding', 'UTF-8', '-nowarn', '-cp', $classes, '-d', $testClasses) + $testSrc
    & (Join-Path $jdk 'bin\javac.exe') $tArgs
    if ($LASTEXITCODE -ne 0) { throw "self-test compile failed ($LASTEXITCODE)" }
}
Write-Host "core compiled OK -> $classes"
