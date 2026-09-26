// Structural checks on a built APK: the requirements that make it installable on Android 11+
// (targetSdk 30+) plus a summary of what ended up inside.
const fs = require('fs');

const file = process.argv[2];
const buf = fs.readFileSync(file);

// End of central directory
let eocd = buf.length - 22;
while (eocd > 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
if (buf.readUInt32LE(eocd) !== 0x06054b50) { console.error('not a zip'); process.exit(1); }
const count = buf.readUInt16LE(eocd + 10);
const cdOffset = buf.readUInt32LE(eocd + 16);

const entries = [];
let p = cdOffset;
for (let i = 0; i < count; i++) {
  if (buf.readUInt32LE(p) !== 0x02014b50) { console.error('bad central directory at', p); process.exit(1); }
  const method = buf.readUInt16LE(p + 10);
  const csize = buf.readUInt32LE(p + 20);
  const usize = buf.readUInt32LE(p + 24);
  const nlen = buf.readUInt16LE(p + 28);
  const elen = buf.readUInt16LE(p + 30);
  const clen = buf.readUInt16LE(p + 32);
  const localOffset = buf.readUInt32LE(p + 42);
  const name = buf.toString('utf8', p + 46, p + 46 + nlen);
  // data offset inside the local header
  const lnlen = buf.readUInt16LE(localOffset + 26);
  const lelen = buf.readUInt16LE(localOffset + 28);
  const dataOffset = localOffset + 30 + lnlen + lelen;
  entries.push({ name, method, csize, usize, localOffset, dataOffset });
  p += 46 + nlen + elen + clen;
}

console.log(`APK: ${file}  (${buf.length} bytes, ${entries.length} entries)`);
console.log('');
let failures = 0;
const required = ['AndroidManifest.xml', 'classes.dex', 'resources.arsc'];
for (const r of required) {
  const e = entries.find(x => x.name === r);
  if (!e) { console.log(`FAIL  missing ${r}`); failures++; }
}
const arsc = entries.find(x => x.name === 'resources.arsc');
if (arsc) {
  const stored = arsc.method === 0;
  const aligned = arsc.dataOffset % 4 === 0;
  console.log(`${stored ? 'ok  ' : 'FAIL'} resources.arsc stored uncompressed (method=${arsc.method})`);
  console.log(`${aligned ? 'ok  ' : 'FAIL'} resources.arsc 4-byte aligned (offset=${arsc.dataOffset}, mod4=${arsc.dataOffset % 4})`);
  if (!stored || !aligned) failures++;
}
const dex = entries.find(x => x.name === 'classes.dex');
if (dex) console.log(`ok   classes.dex present (${dex.usize} bytes)`);
const manifest = entries.find(x => x.name === 'AndroidManifest.xml');
if (manifest) console.log(`ok   AndroidManifest.xml present (${manifest.usize} bytes)`);

// anything stored that should not be, or vice versa
const storedEntries = entries.filter(e => e.method === 0).map(e => e.name);
console.log('');
console.log('stored (uncompressed) entries:', storedEntries.length ? storedEntries.join(', ') : '(none)');

// duplicate names would break installation
const seen = new Set(), dups = [];
for (const e of entries) { if (seen.has(e.name)) dups.push(e.name); seen.add(e.name); }
if (dups.length) { console.log('FAIL  duplicate entries:', dups.join(', ')); failures++; }
else console.log('ok   no duplicate entries');

const sig = entries.filter(e => e.name.startsWith('META-INF/')).map(e => e.name);
console.log('signature entries:', sig.join(', ') || '(none)');

console.log('');
console.log(failures === 0 ? 'APK STRUCTURE OK' : `${failures} STRUCTURAL PROBLEM(S)`);
process.exit(failures === 0 ? 0 : 1);
