# Builds the AMV Converter APK without Gradle.
#
# Why no Gradle: the app has no third-party dependencies, so aapt2 + javac + d8 + apksigner is
# both faster and far more predictable than bootstrapping a 500 MB build system.
#
# Two Windows/Android-toolchain quirks are handled here deliberately:
#  * aapt2 (and other native tools) fail on non-ASCII command-line arguments - this workspace
#    path contains Chinese characters - so every tool is invoked with the working directory set
#    to the project root and *relative* ASCII paths as arguments.
#  * `aapt2 link -R` means "overlay semantics" and rejects an app's own resources; the compiled
#    resource zip must be passed as a positional argument instead.
param(
    [string]$VersionName = '1.0',
    [int]$VersionCode = 1,
    [switch]$SkipClean
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$bt = '.tools/android-sdk/build-tools/33.0.2'
$androidJar = '.tools/android-sdk/platforms/android-33/android.jar'
$jdkRel = (Get-ChildItem (Join-Path $root '.tools\jdk') -Directory | Select-Object -First 1).Name
$jdk = ".tools/jdk/$jdkRel"

Push-Location $root
try {
    foreach ($p in @($bt, $androidJar, $jdk)) {
        if (-not (Test-Path (Join-Path $root $p))) { throw "missing toolchain component: $p" }
    }
    $aapt2 = "$bt/aapt2.exe"
    $zipalign = "$bt/zipalign.exe"
    $d8Jar = "$bt/lib/d8.jar"
    $apksignerJar = "$bt/lib/apksigner.jar"
    $java = "$jdk/bin/java.exe"
    $javac = "$jdk/bin/javac.exe"
    $jar = "$jdk/bin/jar.exe"
    $keytool = "$jdk/bin/keytool.exe"

    $work = '.tools/apkbuild'
    if (-not $SkipClean -and (Test-Path (Join-Path $root $work))) {
        Remove-Item -Recurse -Force (Join-Path $root $work)
    }
    foreach ($d in @('gen', 'classes', 'dex')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $root "$work/$d") | Out-Null
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist') | Out-Null

    function Step($name) { Write-Host "==> $name" }

    # ------------------------------------------------------------ 1. resources
    Step 'aapt2 compile'
    $resZip = "$work/res.zip"
    if (Test-Path (Join-Path $root $resZip)) { Remove-Item -Force (Join-Path $root $resZip) }
    & $aapt2 compile --dir 'app/res' -o $resZip
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

    Step 'aapt2 link'
    $baseApk = "$work/base.apk"
    $genDir = "$work/gen"
    & $aapt2 link -o $baseApk -I $androidJar --manifest 'app/AndroidManifest.xml' $resZip `
        --java $genDir --min-sdk-version 21 --target-sdk-version 33 `
        --version-code $VersionCode --version-name $VersionName
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

    # ------------------------------------------------------------ 2. java
    Step 'javac'
    $classesDir = "$work/classes"
    $sources = @()
    $sources += (Get-ChildItem (Join-Path $root 'core\src') -Recurse -Filter *.java |
        ForEach-Object { $_.FullName.Substring($root.Length + 1) -replace '\\', '/' })
    $sources += (Get-ChildItem (Join-Path $root 'app\src') -Recurse -Filter *.java |
        ForEach-Object { $_.FullName.Substring($root.Length + 1) -replace '\\', '/' })
    $sources += (Get-ChildItem (Join-Path $root $genDir) -Recurse -Filter *.java |
        ForEach-Object { $_.FullName.Substring($root.Length + 1) -replace '\\', '/' })
    Write-Host "    $($sources.Count) source files"
    $javacArgs = @('-encoding', 'UTF-8', '-nowarn', '-source', '8', '-target', '8',
        '-bootclasspath', $androidJar, '-d', $classesDir) + $sources
    & $javac $javacArgs
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

    # ------------------------------------------------------------ 3. dex
    Step 'd8'
    $classesJar = "$work/classes.jar"
    & $jar cf $classesJar -C $classesDir .
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    $dexDir = "$work/dex"
    & $java -cp $d8Jar com.android.tools.r8.D8 --lib $androidJar --min-api 21 --output $dexDir $classesJar
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

    # ------------------------------------------------------------ 4. package
    Step 'package dex into apk'
    $unsigned = "$work/unsigned.apk"
    Copy-Item (Join-Path $root $baseApk) (Join-Path $root $unsigned) -Force
    & $jar uf $unsigned -C $dexDir classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'adding classes.dex failed' }

    # ------------------------------------------------------------ 5. align + sign
    Step 'zipalign'
    $aligned = "$work/aligned.apk"
    & $zipalign -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

    Step 'keystore'
    $keystore = '.tools/keystore/amv.keystore'
    if (-not (Test-Path (Join-Path $root $keystore))) {
        New-Item -ItemType Directory -Force -Path (Join-Path $root '.tools/keystore') | Out-Null
        & $keytool -genkeypair -keystore $keystore -alias amv -storepass amv12345 -keypass amv12345 `
            -keyalg RSA -keysize 2048 -validity 10950 `
            -dname "CN=AMV Converter, OU=Mobile, O=AMV, L=Beijing, ST=Beijing, C=CN"
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    }

    Step 'apksigner'
    $finalApk = "dist/AMV-Converter-$VersionName.apk"
    & $java -jar $apksignerJar sign --ks $keystore --ks-pass pass:amv12345 --key-pass pass:amv12345 `
        --v1-signing-enabled true --v2-signing-enabled true --out $finalApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

    Step 'verify'
    & $java -jar $apksignerJar verify --verbose --print-certs $finalApk |
        Select-String -Pattern 'Verified|Signer #1 certificate DN|SHA-256' | Select-Object -First 6

    $size = (Get-Item (Join-Path $root $finalApk)).Length
    Write-Host ''
    Write-Host ("APK: {0}  ({1:N0} bytes / {2:N2} MB)" -f (Join-Path $root $finalApk), $size, ($size / 1MB))
} finally {
    Pop-Location
}
