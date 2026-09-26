// Byte-level comparison of two raw s16le PCM streams.
const fs = require('fs');
const a = fs.readFileSync(process.argv[2]);
const b = fs.readFileSync(process.argv[3]);
const n = Math.min(a.length, b.length) / 2;
let maxDiff = 0, sumSq = 0, sumSig = 0, firstDiff = -1, diffCount = 0;
for (let i = 0; i < n; i++) {
  const va = a.readInt16LE(i * 2);
  const vb = b.readInt16LE(i * 2);
  const d = va - vb;
  if (d !== 0) { diffCount++; if (firstDiff < 0) firstDiff = i; }
  if (Math.abs(d) > maxDiff) maxDiff = Math.abs(d);
  sumSq += d * d;
  sumSig += va * va;
}
console.log(`samples compared : ${n}  (A=${a.length / 2}, B=${b.length / 2})`);
console.log(`differing samples: ${diffCount}`);
console.log(`max abs diff     : ${maxDiff}`);
console.log(`first difference : ${firstDiff < 0 ? 'none' : 'at sample ' + firstDiff}`);
console.log(`SNR              : ${sumSq === 0 ? 'infinite (bit exact)' : (10 * Math.log10(sumSig / sumSq)).toFixed(2) + ' dB'}`);
console.log(maxDiff === 0 ? 'RESULT: BIT EXACT' : 'RESULT: DIFFERS');
