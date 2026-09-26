# End-to-end validation of the AMV encoder against FFmpeg.
#
#  1. build a synthetic source clip (motion + asymmetric content, so a wrong vertical flip
#     shows up immediately as a PSNR collapse)
#  2. decode it to raw YUV420 + 22050 Hz mono PCM - exactly what the Android MediaCodec
#     layer hands to the encoder core
#  3. run the encoder core through the desktop self-test
#  4. validate the produced .amv structurally (FFprobe + byte-level dump)
#  5. decode it back with FFmpeg and measure PSNR against the ideal scaled source and
#     against FFmpeg's own golden .amv
param(
    [int]$OutFps = 15,
    [string]$Size = '160x120',
    [string]$Fit = 'fit',
    [string]$Tag = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$binDir = Join-Path $root '.tools\ffmpeg\ffmpeg-master-latest-win64-gpl\bin'
$ff = Join-Path $binDir 'ffmpeg.exe'
$ffprobe = Join-Path $binDir 'ffprobe.exe'
$jdk = (Get-ChildItem (Join-Path $root '.tools\jdk') -Directory | Select-Object -First 1).FullName
$lab = Join-Path $root '.tools\lab'
New-Item -ItemType Directory -Force -Path $lab | Out-Null
$logDir = Join-Path $lab 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
if (-not $Tag) { $Tag = "o${OutFps}_$Size" }
$parts = $Size -split 'x'
$w = [int]$parts[0]; $h = [int]$parts[1]

# Native stderr is captured through cmd's own redirection: PowerShell's 2> operator marshals
# native stderr through its error stream, which either aborts the script (Stop) or drops the
# text entirely (SilentlyContinue).
function Invoke-Tool([string]$exe, [string[]]$toolArgs, [string]$logName, [switch]$ReturnStdout) {
    $log = Join-Path $logDir "$logName.log"
    $outFile = Join-Path $logDir "$logName.out"
    $parts = @('"' + $exe + '"')
    foreach ($a in $toolArgs) {
        if ($a -match '[\s&|<>^]') { $parts += '"' + ($a -replace '"', '\"') + '"' } else { $parts += $a }
    }
    $cmdLine = ($parts -join ' ') + ' > "' + $outFile + '" 2> "' + $log + '"'
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    cmd /c $cmdLine | Out-Null
    $ErrorActionPreference = $saved
    $which = if ($ReturnStdout) { $outFile } else { $log }
    if (-not (Test-Path $which)) { return '' }
    $text = Get-Content $which -Raw
    if ($null -eq $text) { return '' }
    return [string]$text
}

$srcMp4 = Join-Path $lab 'src.mp4'
if (-not (Test-Path $srcMp4)) {
    Write-Host '--- building source clip (320x240 @30fps, 4s, testsrc2 + sine) ---'
    Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y',
        '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=30:duration=4',
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=4',
        '-c:v', 'mpeg4', '-q:v', '3', '-c:a', 'aac', '-shortest', $srcMp4) 'srcmp4' | Out-Null
}
$srcYuv = Join-Path $lab 'src_320x240.yuv'
$srcPcm = Join-Path $lab 'src_22050.s16le'
if (-not (Test-Path $srcYuv)) {
    Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $srcMp4, '-f', 'rawvideo', '-pix_fmt', 'yuv420p', $srcYuv) 'srcyuv' | Out-Null
}
if (-not (Test-Path $srcPcm)) {
    Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $srcMp4, '-f', 's16le', '-ac', '1', '-ar', '22050', '-acodec', 'pcm_s16le', $srcPcm) 'srcpcm' | Out-Null
}

$mine = Join-Path $lab "mine_$Tag.amv"
Write-Host "--- encoding ${w}x${h} @ ${OutFps}fps (fit=$Fit) with the AMV core ---"
$classes = Join-Path $root '.tools\corebuild\classes'
$testClasses = Join-Path $root '.tools\corebuild\testclasses'
$javaArgs = @('-cp', "$classes;$testClasses", 'com.amvconverter.selftest.AmvSelfTest',
    'convert', $srcYuv, '320', '240', $srcPcm, '30', '1', "$w", "$h", "$OutFps", '1', $mine, $Fit)
& (Join-Path $jdk 'bin\java.exe') $javaArgs

Write-Host ''
Write-Host '--- FFprobe on the produced file ---'
$probe = Invoke-Tool $ffprobe @('-hide_banner', '-v', 'error', '-show_entries',
    'format=format_name:stream=index,codec_name,codec_type,width,height,pix_fmt,sample_rate,channels,r_frame_rate,nb_frames',
    '-of', 'default=noprint_wrappers=1', $mine) 'probe' -ReturnStdout
Write-Host $probe.Trim()

Write-Host ''
Write-Host '--- decode back with FFmpeg: real errors only (-v error) ---'
$errText = Invoke-Tool $ff @('-hide_banner', '-v', 'error', '-i', $mine, '-f', 'null', '-') 'decode'
if ($errText.Trim().Length -gt 0) { Write-Host "ERRORS:`n$errText" } else { Write-Host 'clean (no errors)' }

Write-Host ''
Write-Host '--- byte-level structure ---'
& node (Join-Path $root 'tools\dump.js') $mine

# ---- picture quality ----
$goldenYuv = Join-Path $lab "golden_${w}x${h}.yuv"
$mineYuv = Join-Path $lab "mine_$Tag.yuv"
# round=down picks the same frame the converter picks (the most recent frame at or before the
# target time), the frame rate must match or the comparison is meaningless, and the golden must
# be FULL range (yuvj420p) because that is what an AMV frame stores.
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $srcMp4,
    '-vf', "fps=${OutFps}:round=down,scale=${w}:${h}", '-pix_fmt', 'yuvj420p', '-f', 'rawvideo', $goldenYuv) 'golden' | Out-Null
$goldenFlip = Join-Path $lab "golden_${w}x${h}_flip.yuv"
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'rawvideo', '-pix_fmt', 'yuv420p', '-s', "${w}x${h}", '-i', $goldenYuv,
    '-vf', 'vflip', '-f', 'rawvideo', '-pix_fmt', 'yuv420p', $goldenFlip) 'goldenflip' | Out-Null
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $mine,
    '-f', 'rawvideo', '-pix_fmt', 'yuv420p', $mineYuv) 'mineyuv' | Out-Null

function Get-Psnr([string]$a, [string]$b, [int]$pw, [int]$ph, [string]$label) {
    $txt = Invoke-Tool $ff @('-hide_banner',
        '-f', 'rawvideo', '-pix_fmt', 'yuv420p', '-s', "${pw}x${ph}", '-i', $a,
        '-f', 'rawvideo', '-pix_fmt', 'yuv420p', '-s', "${pw}x${ph}", '-i', $b,
        '-lavfi', 'psnr', '-f', 'null', '-') 'psnr'
    $lines = @([string]$txt -split "`r?`n")
    $hits = @($lines | Where-Object { $_ -match 'PSNR' })
    $line = if ($hits.Count -gt 0) { [string]$hits[$hits.Count - 1] } else { 'no PSNR line' }
    Write-Host ("{0,-48} {1}" -f $label, (($line -replace '.*?average:', 'average:')).Trim())
}

Write-Host ''
Write-Host '--- picture quality (higher is better; a wrong vertical flip would collapse) ---'
Get-Psnr $mineYuv $goldenYuv $w $h 'my AMV vs ideal scaled source'
Get-Psnr $mineYuv $goldenFlip $w $h 'my AMV vs FLIPPED ideal (self check: must be far lower)'

$ref = Join-Path $lab "ref_$Tag.amv"
$blockSize = [int][math]::Round(22050.0 / $OutFps)
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $srcMp4, '-vf', "scale=${w}:${h}",
    '-r', "$OutFps", '-strict', '-1', '-c:v', 'amv', '-c:a', 'adpcm_ima_amv', '-ar', '22050',
    '-ac', '1', '-block_size', "$blockSize", $ref) 'ref' | Out-Null
$refYuv = Join-Path $lab "ref_$Tag.yuv"
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $ref,
    '-f', 'rawvideo', '-pix_fmt', 'yuv420p', $refYuv) 'refyuv' | Out-Null
Get-Psnr $mineYuv $refYuv $w $h "my AMV vs ffmpeg's own AMV"
Get-Psnr $refYuv $goldenYuv $w $h "ffmpeg's AMV vs ideal (reference point)"

Write-Host ''
Write-Host '--- sizes ---'
Get-Item $mine, $ref | ForEach-Object { "{0,-30} {1,10} bytes" -f $_.Name, $_.Length }

# ---- audio ----
$mineWav = Join-Path $lab "mine_$Tag.s16le"
$refWav = Join-Path $lab "ref_$Tag.s16le"
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $mine, '-f', 's16le', '-acodec', 'pcm_s16le', $mineWav) 'minewav' | Out-Null
Invoke-Tool $ff @('-hide_banner', '-loglevel', 'error', '-y', '-i', $ref, '-f', 's16le', '-acodec', 'pcm_s16le', $refWav) 'refwav' | Out-Null
Write-Host ''
Write-Host '--- decoded audio size (bytes, 2 per sample) ---'
Get-Item $mineWav, $refWav | ForEach-Object { "{0,-30} {1,10} bytes = {2} samples" -f $_.Name, $_.Length, ($_.Length / 2) }
