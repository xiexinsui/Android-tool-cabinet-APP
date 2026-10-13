// 生成应用图标 PNG（纯 Node，无依赖）：圆角方块 + 声波条
const zlib = require('zlib');
const fs = require('fs');

const SIZE = 512, SS = 2, RAW = SIZE * SS;

function hex(h) {
  const n = parseInt(h.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

const bgTop = hex('#00897B'), bgBot = hex('#004D40'), fg = hex('#FFFFFF');

function insideRoundRect(x, y, w, h, r) {
  return (px, py) => {
    if (px < x || px >= x + w || py < y || py >= y + h) return false;
    if (px >= x + r && px < x + w - r) return true;
    if (py >= y + r && py < y + h - r) return true;
    const cx = px < x + r ? x + r : x + w - r - 1;
    const cy = py < y + r ? y + r : y + h - r - 1;
    return (px - cx) ** 2 + (py - cy) ** 2 <= (r - 1) ** 2;
  };
}

function roundedBar(px, py, x, y, w, h, r) {
  return insideRoundRect(x, y, w, h, Math.min(r, w / 2))(px, py);
}

const inBg = insideRoundRect(30, 30, RAW - 60, RAW - 60, 160);

// 工具箱图标：2x2 圆角方格
const cellGap = Math.round(RAW * 0.04);
const cellSize = Math.round((RAW * 0.58 - cellGap) / 2);
const gridStart = Math.round((RAW - (cellSize * 2 + cellGap)) / 2);
const cells = [
  [gridStart, gridStart],
  [gridStart + cellSize + cellGap, gridStart],
  [gridStart, gridStart + cellSize + cellGap],
  [gridStart + cellSize + cellGap, gridStart + cellSize + cellGap],
];

const raw = new Uint8Array(RAW * RAW * 4);
for (let y = 0; y < RAW; y++) {
  for (let x = 0; x < RAW; x++) {
    let col = null;
    if (inBg(x, y)) {
      const t = y / RAW;
      col = [
        Math.round(bgTop[0] + (bgBot[0] - bgTop[0]) * t),
        Math.round(bgTop[1] + (bgBot[1] - bgTop[1]) * t),
        Math.round(bgTop[2] + (bgBot[2] - bgTop[2]) * t),
      ];
      for (const [cx, cy] of cells) {
        if (roundedBar(x, y, cx, cy, cellSize, cellSize, 34)) {
          col = fg;
          break;
        }
      }
    }
    const o = (y * RAW + x) * 4;
    raw[o] = col ? col[0] : 0;
    raw[o + 1] = col ? col[1] : 0;
    raw[o + 2] = col ? col[2] : 0;
    raw[o + 3] = col ? 255 : 0;
  }
}

// 降采样到 SIZE
const out = Buffer.alloc(SIZE * SIZE * 4);
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let dy = 0; dy < SS; dy++) {
      for (let dx = 0; dx < SS; dx++) {
        const o = ((y * SS + dy) * RAW + x * SS + dx) * 4;
        r += raw[o]; g += raw[o + 1]; b += raw[o + 2]; a += raw[o + 3];
      }
    }
    const k = SS * SS, p = (y * SIZE + x) * 4;
    out[p] = a ? Math.round(r / a) : 0;
    out[p + 1] = a ? Math.round(g / a) : 0;
    out[p + 2] = a ? Math.round(b / a) : 0;
    out[p + 3] = Math.round(a / k);
  }
}

// PNG 编码
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
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0); ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; ihdr[9] = 6; // RGBA8
const rawRows = Buffer.alloc(SIZE * (SIZE * 4 + 1));
for (let y = 0; y < SIZE; y++) {
  rawRows[y * (SIZE * 4 + 1)] = 0;
  out.copy(rawRows, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(rawRows, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);
fs.mkdirSync(process.argv[2], { recursive: true });
fs.writeFileSync(process.argv[2] + '/ic_launcher.png', png);
console.log('written', png.length, 'bytes');
