// 由 assets/icon_source_raw.png 生成全部启动图标资源（纯 Node，零依赖）。
// 用法：node tools/gen_icons.js
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const RAW = path.join(ROOT, 'assets', 'icon_source_raw.png');
const MASTER = path.join(ROOT, 'assets', 'icon_master.png');
const RES = path.join(ROOT, 'app', 'src', 'main', 'res');
const MASTER_SIZE = 512;
const FG_SIZE = 432;
const DENSITIES = { 'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96, 'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192 };
// 方形图标占画布比例；圆形图标是圆盘 + 盘内图形，比例另算。
const SQUARE_FILL = 0.61;
const ROUND_FILL = 0.66;
const FG_FILL = 0.62;

// ---------- PNG 读写 ----------
function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('不是 PNG: ' + file);
  let pos = 8, bitDepth = 0, colorType = 0, interlace = 0;
  const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20);
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      bitDepth = data[8]; colorType = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    pos += 12 + len;
  }
  if (bitDepth !== 8 || interlace !== 0 || (colorType !== 2 && colorType !== 6)) {
    throw new Error(`仅支持 8bit 非隔行的 RGB/RGBA，实际 depth=${bitDepth} color=${colorType} interlace=${interlace}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const stride = w * bpp;
  const src = zlib.inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(h * stride);
  const prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filter = src[y * (stride + 1)];
    const rowStart = y * (stride + 1) + 1;
    for (let x = 0; x < stride; x++) {
      const raw = src[rowStart + x];
      const a = x >= bpp ? out[y * stride + x - bpp] : 0;
      const b = prev[x];
      const c = x >= bpp ? prev[x - bpp] : 0;
      let v;
      if (filter === 0) v = raw;
      else if (filter === 1) v = raw + a;
      else if (filter === 2) v = raw + b;
      else if (filter === 3) v = raw + ((a + b) >> 1);
      else {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v = raw + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      }
      out[y * stride + x] = v & 255;
    }
    out.copy(prev, 0, y * stride, (y + 1) * stride);
  }
  const px = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    px[i * 4] = out[i * bpp];
    px[i * 4 + 1] = out[i * bpp + 1];
    px[i * 4 + 2] = out[i * bpp + 2];
    px[i * 4 + 3] = colorType === 6 ? out[i * bpp + 3] : 255;
  }
  return { w, h, data: px };
}

const crcTable = [];
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
  crcTable[n] = c >>> 0;
}
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 255] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function encodePng(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  const stride = w * 4;
  const rows = Buffer.alloc(h * (stride + 1));
  for (let y = 0; y < h; y++) {
    rows[y * (stride + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride)
      .copy(rows, y * (stride + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(rows, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
function writePng(file, w, h, rgba) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, encodePng(w, h, rgba));
  console.log('  ->', path.relative(ROOT, file));
}

// ---------- 图像处理 ----------
// 区域平均缩放：把 src 的 [sx0,sy0)-[sx1,sy1) 区域画到 dst 的 dsize×dsize 画布中，
// 居中放在 (dx0,dy0) 起、边长 dside 的方框内。每个目标像素按缩放倍数决定采样网格。
function drawScaled(src, sw, sh, sx0, sy0, sx1, sy1, dst, dsize, dx0, dy0, dside) {
  const ratio = (sx1 - sx0) / dside;
  const k = Math.max(2, Math.min(8, Math.ceil(ratio)));
  for (let y = 0; y < dside; y++) {
    for (let x = 0; x < dside; x++) {
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      for (let sy = 0; sy < k; sy++) {
        for (let sx = 0; sx < k; sx++) {
          const fx = sx0 + (x + (sx + 0.5) / k) * ratio;
          const fy = sy0 + (y + (sy + 0.5) / k) * ((sy1 - sy0) / dside);
          const px = Math.min(sw - 1, Math.max(0, Math.floor(fx)));
          const py = Math.min(sh - 1, Math.max(0, Math.floor(fy)));
          const o = (py * sw + px) * 4;
          const al = src[o + 3];
          r += src[o] * al; g += src[o + 1] * al; b += src[o + 2] * al; a += al;
          n++;
        }
      }
      const p = ((dy0 + y) * dsize + dx0 + x) * 4;
      dst[p] = a ? Math.round(r / a) : 0;
      dst[p + 1] = a ? Math.round(g / a) : 0;
      dst[p + 2] = a ? Math.round(b / a) : 0;
      dst[p + 3] = Math.round(a / n);
    }
  }
}

// 把与画布边缘连通的近白区域改成透明：图标本体是圆角方框，四角原本是白底不透明，
// 会被 lint 判为「铺满整个方形区域」，也导致启动器上看不出圆角。
function clearOuterBackground(img, w, h, threshold) {
  const seen = new Uint8Array(w * h);
  const queue = [];
  const push = (x, y) => {
    const i = y * w + x;
    if (seen[i]) return;
    const o = i * 4;
    if (Math.min(img[o], img[o + 1], img[o + 2]) < threshold) return;
    seen[i] = 1;
    img[o + 3] = 0;
    queue.push(i);
  };
  for (let x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
  for (let y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
  while (queue.length) {
    const i = queue.pop();
    const x = i % w, y = (i - x) / w;
    if (x > 0) push(x - 1, y);
    if (x < w - 1) push(x + 1, y);
    if (y > 0) push(x, y - 1);
    if (y < h - 1) push(x, y + 1);
  }
}

function boundingBox(img, w, h, threshold) {
  let minX = w, minY = h, maxX = -1, maxY = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      if (Math.min(img[o], img[o + 1], img[o + 2]) < threshold) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  return { minX, minY, maxX, maxY };
}

// ---------- 主流程 ----------
const raw = decodePng(RAW);
console.log(`源图 ${raw.w} x ${raw.h}`);
const img = raw.data;

// 源图右下角带生成器水印，先按矩形覆盖为白，避免进入成品图标。
const wmX = Math.floor(raw.w * 0.808), wmY = Math.floor(raw.h * 0.925);
for (let y = wmY; y < raw.h; y++) {
  for (let x = wmX; x < raw.w; x++) {
    const o = (y * raw.w + x) * 4;
    img[o] = img[o + 1] = img[o + 2] = 255;
  }
}

const box = boundingBox(img, raw.w, raw.h, 248);
const cropW = box.maxX - box.minX + 1, cropH = box.maxY - box.minY + 1;
const side = Math.max(cropW, cropH);
const cx = box.minX + cropW / 2, cy = box.minY + cropH / 2;
let sx0 = Math.max(0, Math.round(cx - side / 2));
let sy0 = Math.max(0, Math.round(cy - side / 2));
if (sx0 + side > raw.w) sx0 = raw.w - side;
if (sy0 + side > raw.h) sy0 = raw.h - side;
console.log(`图形包围盒 ${cropW} x ${cropH}，取方框 ${side} @ (${sx0},${sy0})`);

// 母图：方形裁切区缩到 512，四角白底转透明。
const master = new Uint8Array(MASTER_SIZE * MASTER_SIZE * 4);
drawScaled(img, raw.w, raw.h, sx0, sy0, sx0 + side, sy0 + side, master, MASTER_SIZE, 0, 0, MASTER_SIZE);
clearOuterBackground(master, MASTER_SIZE, MASTER_SIZE, 248);
writePng(MASTER, MASTER_SIZE, MASTER_SIZE, master);

function canvas(size) {
  return new Uint8Array(size * size * 4);
}
function centered(dst, size, fill) {
  const inner = Math.round(size * fill);
  return { x: Math.round((size - inner) / 2), y: Math.round((size - inner) / 2), inner };
}

for (const [dir, size] of Object.entries(DENSITIES)) {
  // 方形传统图标：图形居中，四周留透明安全区。
  const sq = canvas(size);
  const s = centered(sq, size, SQUARE_FILL);
  drawScaled(master, MASTER_SIZE, MASTER_SIZE, 0, 0, MASTER_SIZE, MASTER_SIZE, sq, size, s.x, s.y, s.inner);
  writePng(path.join(RES, dir, 'ic_launcher.png'), size, size, sq);

  // 圆形传统图标：不透明白圆盘 + 盘内图形，与方形版像素不同且形状为圆形。
  const rd = canvas(size);
  const r = size / 2 - 0.5;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2);
      if (d <= r) {
        const edge = Math.max(0, Math.min(1, r - d));
        const o = (y * size + x) * 4;
        rd[o] = rd[o + 1] = rd[o + 2] = 255;
        rd[o + 3] = Math.round(255 * Math.min(1, edge));
      }
    }
  }
  const c = centered(rd, size, ROUND_FILL);
  const art = canvas(size);
  drawScaled(master, MASTER_SIZE, MASTER_SIZE, 0, 0, MASTER_SIZE, MASTER_SIZE, art, size, c.x, c.y, c.inner);
  for (let i = 0; i < size * size; i++) {
    const o = i * 4;
    if (art[o + 3] === 0) continue;
    const a = art[o + 3] / 255;
    rd[o] = Math.round(rd[o] * (1 - a) + art[o] * a);
    rd[o + 1] = Math.round(rd[o + 1] * (1 - a) + art[o + 1] * a);
    rd[o + 2] = Math.round(rd[o + 2] * (1 - a) + art[o + 2] * a);
    rd[o + 3] = Math.max(rd[o + 3], art[o + 3]);
  }
  writePng(path.join(RES, dir, 'ic_launcher_round.png'), size, size, rd);
}

// 自适应图标前景：图形缩到安全区，背景留透明（底色由 @color/ic_launcher_background 提供）。
const fg = canvas(FG_SIZE);
const f = centered(fg, FG_SIZE, FG_FILL);
drawScaled(master, MASTER_SIZE, MASTER_SIZE, 0, 0, MASTER_SIZE, MASTER_SIZE, fg, FG_SIZE, f.x, f.y, f.inner);
writePng(path.join(RES, 'drawable-nodpi', 'ic_launcher_foreground.png'), FG_SIZE, FG_SIZE, fg);

// 单色层：Android 13+ 主题图标用，取图形的墨色做白色剪影。
const mono = canvas(FG_SIZE);
for (let y = 0; y < FG_SIZE; y++) {
  for (let x = 0; x < FG_SIZE; x++) {
    const sy = Math.floor((y - f.y) / (f.inner / MASTER_SIZE));
    const sx = Math.floor((x - f.x) / (f.inner / MASTER_SIZE));
    const o = (y * FG_SIZE + x) * 4;
    if (sy < 0 || sx < 0 || sy >= MASTER_SIZE || sx >= MASTER_SIZE) continue;
    const m = (sy * MASTER_SIZE + sx) * 4;
    if (master[m + 3] === 0) continue;
    const lum = (master[m] * 0.299 + master[m + 1] * 0.587 + master[m + 2] * 0.114) / 255;
    const alpha = Math.round((1 - lum) * master[m + 3]);
    mono[o] = mono[o + 1] = mono[o + 2] = 255;
    mono[o + 3] = alpha;
  }
}
writePng(path.join(RES, 'drawable-nodpi', 'ic_launcher_monochrome.png'), FG_SIZE, FG_SIZE, mono);
console.log('完成');
