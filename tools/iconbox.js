// Locate the blue app-icon square inside a padded source image (raw RGB24 input).
const fs = require('fs');
const [, , file, wStr, hStr] = process.argv;
const w = parseInt(wStr, 10), h = parseInt(hStr, 10);
const b = fs.readFileSync(file);
let minX = w, maxX = -1, minY = h, maxY = -1, n = 0;
for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    const o = (y * w + x) * 3;
    const r = b[o], g = b[o + 1], bl = b[o + 2];
    // blue-ish: channel order matters (the blue square dominates)
    if (bl > r + 40 && bl > 110 && g < bl) {
      n++;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
}
console.log('blue pixels:', n);
console.log(`blue bbox: x ${minX}..${maxX} y ${minY}..${maxY}  size ${maxX - minX + 1}x${maxY - minY + 1}`);
const cw = maxX - minX + 1, ch = maxY - minY + 1;
const s = Math.min(cw, ch);
console.log(`CROP=${s}:${s}:${minX}:${minY}`);
