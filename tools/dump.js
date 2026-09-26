// Precise structural dump of an AMV file. AMV chunks are NOT padded (ffmpeg amvenc.c).
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const u32 = (o) => buf.readUInt32LE(o);
const u16 = (o) => buf.readUInt16LE(o);
const fc = (o) => buf.toString('latin1', o, o + 4);
const hex = (o, n) => Buffer.from(buf.subarray(o, o + n)).toString('hex').replace(/(..)/g, '$1 ').trim();

console.log('file:', process.argv[2], buf.length, 'bytes');
console.log(`RIFF form="${fc(8)}" riffsize_field=${u32(4)}`);

// header walk: sizes are deliberately 0, so walk by known field layout
let o = 12;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} type="${fc(o + 8)}"`);
o += 12; // LIST hdrl
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)}  <- amvh`);
const a = o + 8;
console.log(`   us_per_frame=${u32(a)} w=${u32(a + 32)} h=${u32(a + 36)} rate_den=${u32(a + 40)} rate_num=${u32(a + 44)} zero=${u32(a + 48)} durfield=[ss=${buf[a + 52]} mm=${buf[a + 53]} hh=${u16(a + 54)}]`);
o = a + 56;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} type="${fc(o + 8)}"`);
o += 12;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} (zeros=${buf.subarray(o + 8, o + 8 + u32(o + 4)).every(x => x === 0)})`);
o += 8 + u32(o + 4);
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} (zeros=${buf.subarray(o + 8, o + 8 + u32(o + 4)).every(x => x === 0)})`);
o += 8 + u32(o + 4);
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} type="${fc(o + 8)}"`);
o += 12;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} (zeros=${buf.subarray(o + 8, o + 8 + u32(o + 4)).every(x => x === 0)})`);
o += 8 + u32(o + 4);
const sfOff = o + 8;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)}`);
console.log(`   WAVEFORMATEX: tag=${u16(sfOff)} ch=${u16(sfOff + 2)} rate=${u32(sfOff + 4)} avgbytes=${u32(sfOff + 8)} blockalign=${u16(sfOff + 12)} bits=${u16(sfOff + 14)} cbSize=${u16(sfOff + 16)} pad=${u16(sfOff + 18)}`);
o += 8 + u32(o + 4);
const moviHdr = o;
console.log(`@${o} "${fc(o)}" size=${u32(o + 4)} type="${fc(o + 8)}"`);
let m = o + 12;

// chunk walk, NO padding
let n = 0, vsz = [], asz = [], order = [];
let firstV = -1, firstVsz = 0, firstA = -1, firstAsz = 0;
while (m + 8 <= buf.length && (fc(m) === '00dc' || fc(m) === '01wb')) {
  const id = fc(m), sz = u32(m + 4);
  if (n < 10) order.push(`${id}(${sz})`);
  if (id === '00dc') { vsz.push(sz); if (firstV < 0) { firstV = m + 8; firstVsz = sz; } }
  else { asz.push(sz); if (firstA < 0) { firstA = m + 8; firstAsz = sz; } }
  m += 8 + sz; n++;
}
console.log(`\nchunks=${n} walk ended @${m} tag="${fc(m)}" (tail ${buf.length - m} bytes)`);
console.log('first order:', order.join(' '));
console.log(`video frames=${vsz.length} sizes min=${Math.min(...vsz)} max=${Math.max(...vsz)} first3=${vsz.slice(0, 3)}`);
console.log(`audio blocks=${asz.length} uniq sizes=${[...new Set(asz)].join(',')}`);
const odd = vsz.filter(s => s & 1).length;
console.log(`odd-sized video frames=${odd} (confirms no pad bytes)`);

console.log(`\n--- tail 16 bytes: ${hex(buf.length - 16, 16)} ascii=${JSON.stringify(buf.toString('latin1', buf.length - 16))}`);

console.log('\n--- first video frame ---');
console.log(`size=${firstVsz} head=${hex(firstV, 16)} tail=${hex(firstV + firstVsz - 4, 4)}`);
console.log(`starts SOI=${buf[firstV] === 0xff && buf[firstV + 1] === 0xd8} ends EOI=${buf[firstV + firstVsz - 2] === 0xff && buf[firstV + firstVsz - 1] === 0xd9}`);
// count 0xFF bytes and stuffing
let ffCount = 0, stuffed = 0, bad = 0;
for (let i = firstV + 2; i < firstV + firstVsz - 2; i++) {
  if (buf[i] === 0xff) { ffCount++; if (buf[i + 1] === 0x00) stuffed++; else bad++; }
}
console.log(`entropy bytes=${firstVsz - 4} FF_count=${ffCount} FF00_stuffed=${stuffed} unstuffed_FF=${bad}`);

console.log('\n--- first audio block ---');
console.log(`size=${firstAsz} (expected block_align)`);
console.log(hex(firstA, 16));
console.log(`predictor=${buf.readInt16LE(firstA)} step_index=${buf[firstA + 2]} reserved=${buf[firstA + 3]} frame_size=${u32(firstA + 4)}`);
console.log(`nibbles available=${(firstAsz - 8) * 2}`);
