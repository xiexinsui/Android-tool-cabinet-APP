Add-Type -AssemblyName System.Drawing

# 【历史脚本 / 调试用】分析源图：找出非白像素的包围盒，以及右下角水印的位置范围。
# 用法：powershell -File tools/analyze_icon.ps1 [-Path 图片路径]
param(
    [string]$Path = (Join-Path $PSScriptRoot '..\assets\icon_source_raw.png')
)

$bmp = New-Object System.Drawing.Bitmap($Path)
$w = $bmp.Width
$h = $bmp.Height
Write-Output "size=$w x $h"

$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                     [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)

$threshold = 248
$minX = $w; $minY = $h; $maxX = -1; $maxY = -1
# 只统计右下象限（水印所在区域）
$qMinX = $w; $qMinY = $h; $qMaxX = -1; $qMaxY = -1
for ($y = 0; $y -lt $h; $y += 2) {
    $row = $y * $stride
    for ($x = 0; $x -lt $w; $x += 2) {
        $i = $row + $x * 4
        $b = $bytes[$i]; $g = $bytes[$i + 1]; $r = $bytes[$i + 2]
        if ($r -lt $threshold -or $g -lt $threshold -or $b -lt $threshold) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
            if ($x -gt ($w * 0.62) -and $y -gt ($h * 0.86)) {
                if ($x -lt $qMinX) { $qMinX = $x }
                if ($x -gt $qMaxX) { $qMaxX = $x }
                if ($y -lt $qMinY) { $qMinY = $y }
                if ($y -gt $qMaxY) { $qMaxY = $y }
            }
        }
    }
}
Write-Output "all-ink bbox: x=$minX..$maxX y=$minY..$maxY"
if ($qMaxX -ge 0) {
    Write-Output "corner-ink bbox: x=$qMinX..$qMaxX y=$qMinY..$qMaxY"
} else {
    Write-Output "corner-ink: none"
}
Write-Output ("ratios: inkBottom={0:N3} inkRight={1:N3} cornerTop={2:N3}" -f `
    ($maxY / $h), ($maxX / $w), ($(if ($qMinY -ge 0) { $qMinY / $h } else { -1 })))
$bmp.Dispose()
