// Find the bounding box of non-near-white pixels in a raw RGB24 image.
const fs = require('fs');
const [, , file, wStr, hStr] = process.argv;
const w = parseInt(wStr, 10), h = parseInt(hStr, 10);
const buf = fs.readFileSync(file);
let minX = w, maxX = -1, minY = h, maxY = -1;
for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    const o = (y * w + x) * 3;
    const r = buf[o], g = buf[o + 1], b = buf[o + 2];
    // near-white background / watermark -> ignore
    if (r > 238 && g > 238 && b > 238) continue;
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
}
console.log(`image ${w}x${h}`);
console.log(`content bbox: x ${minX}..${maxX}  y ${minY}..${maxY}  (${maxX - minX + 1}x${maxY - minY + 1})`);
const cw = maxX - minX + 1, ch = maxY - minY + 1;
console.log(`crop=${cw}:${ch}:${minX}:${minY}`);
