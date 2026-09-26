// Y-plane statistics for rawvideo YUV420 files: min / max / mean / histogram ends.
const fs = require('fs');
function stats(file, w, h, frames) {
  const buf = fs.readFileSync(file);
  const frameBytes = w * h * 3 / 2;
  const total = Math.floor(buf.length / frameBytes);
  const n = Math.min(frames || 1, total);
  let min = 255, max = 0, sum = 0, cnt = 0;
  const hist = new Array(256).fill(0);
  for (let f = 0; f < n; f++) {
    const off = f * frameBytes;
    for (let i = 0; i < w * h; i++) {
      const v = buf[off + i];
      if (v < min) min = v;
      if (v > max) max = v;
      sum += v; cnt++;
      hist[v]++;
    }
  }
  return { frames: total, min, max, mean: (sum / cnt).toFixed(2), at0: hist[0], at16: hist[16], at235: hist[235], at255: hist[255] };
}
const files = process.argv.slice(2);
for (let i = 0; i < files.length; i += 3) {
  const label = files[i + 2];
  const s = stats(files[i], parseInt(files[i + 1].split('x')[0], 10), parseInt(files[i + 1].split('x')[1], 10), 3);
  console.log(`${label.padEnd(22)} Y min=${String(s.min).padStart(3)} max=${String(s.max).padStart(3)} mean=${String(s.mean).padStart(6)}  count@0=${s.at0} @16=${s.at16} @235=${s.at235} @255=${s.at255}`);
}
