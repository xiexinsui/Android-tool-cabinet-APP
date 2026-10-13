Add-Type -AssemblyName System.Drawing

# 【历史脚本 / 可选】由源图生成「工具箱」图标母图：
#   1. 覆盖右下角的生成器水印矩形（只碰水印区，不碰图形本体）
#   2. 按非白像素包围盒紧裁，取正方形区域
#   3. 输出各密度传统启动图标 + 自适应图标前景
# 现在入库的成品资源由 tools/gen_icons.js（纯 Node、零依赖）生成，
# 本脚本仅在需要重新裁切/复核母图时使用。
# 用法：powershell -File tools/make_icon_from_png.ps1 [-Source 图片路径]

param(
    [string]$Source = (Join-Path $PSScriptRoot '..\assets\icon_source_raw.png')
)

$resRoot = Join-Path $PSScriptRoot '..\app\src\main\res'
$threshold = 248          # 低于此值算「有内容」（源图背景是纯白）

function Load-Pixels([System.Drawing.Bitmap]$bmp) {
    $rect = New-Object System.Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                          [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $bmp.Height)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)
    return @{ bytes = $bytes; stride = $stride }
}

function Save-Bitmap([System.Drawing.Bitmap]$bmp, [string]$path) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output "  -> $path"
}

$src = New-Object System.Drawing.Bitmap($Source)
$w = $src.Width
$h = $src.Height
Write-Output "源图 $w x $h"

# ---- 1. 去水印：直接涂白（水印在右下角，图形本体的圆角框在 x<0.81、y<0.81 之内） ----
$wmX = [int]($w * 0.808)
$wmY = [int]($h * 0.925)
$watermark = New-Object System.Drawing.Rectangle($wmX, $wmY, ($w - $wmX), ($h - $wmY))
$g = [System.Drawing.Graphics]::FromImage($src)
$g.SetClip($watermark)
$g.Clear([System.Drawing.Color]::White)
$g.ResetClip()
$g.Dispose()

# ---- 2. 找非白像素包围盒 ----
$p = Load-Pixels $src
$bytes = $p.bytes
$stride = $p.stride
$minX = $w; $minY = $h; $maxX = -1; $maxY = -1
for ($y = 0; $y -lt $h; $y++) {
    $row = $y * $stride
    for ($x = 0; $x -lt $w; $x++) {
        $i = $row + $x * 4
        if ($bytes[$i + 2] -lt $threshold -or $bytes[$i + 1] -lt $threshold -or $bytes[$i] -lt $threshold) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
if ($maxX -lt 0) { throw '没找到任何图形像素' }
$cropW = $maxX - $minX + 1
$cropH = $maxY - $minY + 1
Write-Output ("图形包围盒 x={0}..{1} y={2}..{3}（{4} x {5}）" -f $minX, $maxX, $minY, $maxY, $cropW, $cropH)

# 取正方形裁切区（源图基本对称，按长边取方框，避免图标被拉扁）
$side = [Math]::Max($cropW, $cropH)
$cx = $minX + $cropW / 2.0
$cy = $minY + $cropH / 2.0
$x0 = [Math]::Max(0, [int]($cx - $side / 2.0))
$y0 = [Math]::Max(0, [int]($cy - $side / 2.0))
if ($x0 + $side -gt $w) { $x0 = $w - $side }
if ($y0 + $side -gt $h) { $y0 = $h - $side }
$cropRect = New-Object System.Drawing.Rectangle($x0, $y0, $side, $side)
$cropped = New-Object System.Drawing.Bitmap($side, $side, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gc = [System.Drawing.Graphics]::FromImage($cropped)
$gc.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gc.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$gc.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $side, $side)), $cropRect,
              [System.Drawing.GraphicsUnit]::Pixel)
$gc.Dispose()
$src.Dispose()

# ---- 3. 传统图标：图形铺满，四周留 2% 边距（遮罩成圆形时不至于切到边框线） ----
$targets = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}
foreach ($name in $targets.Keys | Sort-Object { $targets[$_] }) {
    $size = $targets[$name]
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gd = [System.Drawing.Graphics]::FromImage($bmp)
    $gd.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gd.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gd.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $gd.Clear([System.Drawing.Color]::White)
    $pad = [int][Math]::Round($size * 0.02)
    $inner = $size - (2 * $pad)
    $dest = New-Object System.Drawing.Rectangle($pad, $pad, $inner, $inner)
    $gd.DrawImage($cropped, $dest)
    $gd.Dispose()
    Save-Bitmap $bmp (Join-Path $resRoot "$name\ic_launcher.png")
    Save-Bitmap $bmp (Join-Path $resRoot "$name\ic_launcher_round.png")
    $bmp.Dispose()
}

# ---- 4. 自适应图标前景：图形只占画布 62%，落在启动器的圆形安全区内 ----
$fgSize = 432
$fg = New-Object System.Drawing.Bitmap($fgSize, $fgSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gf = [System.Drawing.Graphics]::FromImage($fg)
$gf.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gf.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$gf.Clear([System.Drawing.Color]::White)
$draw = [int][Math]::Round($fgSize * 0.62)
$off = [int](($fgSize - $draw) / 2)
$gf.DrawImage($cropped, (New-Object System.Drawing.Rectangle($off, $off, $draw, $draw)))
$gf.Dispose()
Save-Bitmap $fg (Join-Path $resRoot 'drawable-nodpi\ic_launcher_foreground.png')
$fg.Dispose()
$cropped.Dispose()

Write-Output '完成'
