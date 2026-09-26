// Compare Y plane profiles between two 160x120 rawvideo YUV420 files.
const fs = require('fs');
const [, , fileA, fileB, labelA, labelB] = process.argv;
const W = 160, H = 120;
const FRAME = W * H * 3 / 2;

function frame(f, index) {
  const buf = fs.readFileSync(f);
  return buf.subarray(index * FRAME, index * FRAME + FRAME);
}

const a = frame(fileA, 0);
const b = frame(fileB, 0);
const lab = { a: labelA || 'A', b: labelB || 'B' };

console.log(`comparing ${lab.a} vs ${lab.b} (frame 0, Y plane)`);
console.log('\nvertical profile at x=80:');
console.log('row'.padStart(4), lab.a.padStart(8), lab.b.padStart(8), 'diff'.padStart(6));
for (let y = 0; y < H; y += 8) {
  const va = a[y * W + 80] & 0xFF;
  const vb = b[y * W + 80] & 0xFF;
  console.log(String(y).padStart(4), String(va).padStart(8), String(vb).padStart(8), String(va - vb).padStart(6));
}

console.log('\nhorizontal profile at y=60:');
console.log('col'.padStart(4), lab.a.padStart(8), lab.b.padStart(8), 'diff'.padStart(6));
for (let x = 0; x < W; x += 10) {
  const va = a[60 * W + x] & 0xFF;
  const vb = b[60 * W + x] & 0xFF;
  console.log(String(x).padStart(4), String(va).padStart(8), String(vb).padStart(8), String(va - vb).padStart(6));
}

// statistics over the whole frame
let sum = 0, sumSq = 0, n = 0, maxAbs = 0, sumA = 0, sumB = 0;
for (let i = 0; i < W * H; i++) {
  const d = (a[i] & 0xFF) - (b[i] & 0xFF);
  sum += d; sumSq += d * d; n++;
  maxAbs = Math.max(maxAbs, Math.abs(d));
  sumA += a[i] & 0xFF; sumB += b[i] & 0xFF;
}
const mean = sum / n;
const rms = Math.sqrt(sumSq / n);
console.log(`\nY plane stats: mean bias=${mean.toFixed(2)} rms=${rms.toFixed(2)} maxAbs=${maxAbs}`);
console.log(`mean level: ${lab.a}=${(sumA / n).toFixed(2)}  ${lab.b}=${(sumB / n).toFixed(2)}`);
console.log(`PSNR from rms = ${(20 * Math.log10(255 / Math.max(1e-9, rms))).toFixed(2)} dB`);
