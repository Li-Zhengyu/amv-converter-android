// Extract the fixed sp5x (AMV) tables from FFmpeg's sp5x.h and emit a Java source file.
// Doing this by code generation instead of transcription removes any chance of a typo.
const fs = require('fs');

const h = fs.readFileSync(process.argv[2], 'utf8');
const out = process.argv[3];

function arr(name) {
  const m = h.match(new RegExp(name + '\\s*(?:\\[\\]\\s*)?(?:\\[[^\\]]*\\]\\s*)*=\\s*\\{([\\s\\S]*?)\\};'));
  if (!m) throw new Error('not found: ' + name);
  const nums = [];
  const body = m[1].replace(/\/\*[\s\S]*?\*\//g, '').replace(/[{}]/g, '');
  for (const tok of body.split(',')) {
    const t = tok.trim();
    if (!t) continue;
    const v = t.match(/^0x[0-9a-fA-F]+$/) ? parseInt(t, 16) : parseInt(t, 10);
    if (Number.isNaN(v)) throw new Error('bad token in ' + name + ': ' + JSON.stringify(t));
    nums.push(v);
  }
  return nums;
}

const dht = arr('sp5x_data_dht');
const q = arr('sp5x_qscale_five_quant_table');

console.log('dht bytes =', dht.length, (dht.length === 420 ? 'OK (2+2+416)' : 'UNEXPECTED'));
console.log('dht header =', dht.slice(0, 4).map(x => x.toString(16)));
console.log('q values =', q.length);

// parse DHT payload (skip FF C4 len)
const dhtLen = (dht[2] << 8) | dht[3];
console.log('dht declared len =', dhtLen, 'payload', dht.length - 4);
let p = 4;
const tables = [];
while (p < dht.length) {
  const tcth = dht[p++];
  const counts = dht.slice(p, p + 16); p += 16;
  const total = counts.reduce((a, b) => a + b, 0);
  const vals = dht.slice(p, p + total); p += total;
  tables.push({ tc: tcth >> 4, th: tcth & 15, counts, vals });
}
console.log('\ntables parsed:', tables.length);
for (const t of tables) {
  console.log(`  class=${t.tc} id=${t.th} symbols=${t.vals.length} counts=[${t.counts.join(',')}]`);
}

// sanity: standard Annex K
const stdDCLum = [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0];
const stdACLum = [0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d];
console.log('DC-lum counts match Annex K:', JSON.stringify(tables[0].counts) === JSON.stringify(stdDCLum));
console.log('AC-lum counts match Annex K:', JSON.stringify(tables[1].counts) === JSON.stringify(stdACLum));
console.log('DC-lum values 0..11:', tables[0].vals.join(','));
// completeness check: AC tables must cover EOB(0x00), ZRL(0xF0) and all (run,size) 0..9
const ac = tables[1].vals;
const sizes = new Set(ac.map(v => v & 15));
console.log('AC-lum has EOB:', ac.includes(0x00), ' ZRL:', ac.includes(0xf0), ' sizes present:', [...sizes].sort((a, b) => a - b).join(','));

// emit java
function jArr(name, nums, perLine = 12) {
  const lines = [];
  for (let i = 0; i < nums.length; i += perLine) lines.push('        ' + nums.slice(i, i + perLine).join(', ') + (i + perLine < nums.length ? ',' : ''));
  return `    public static final int[] ${name} = {\n${lines.join('\n')}\n    };\n`;
}

let s = '';
s += 'package com.amvconverter.core;\n\n';
s += '/**\n';
s += ' * Fixed AMV (sp5x) tables, extracted automatically from FFmpeg libavcodec/sp5x.h.\n';
s += ' *\n';
s += ' * These are NOT negotiable: the AMV decoder synthesises a JPEG header from its own copies\n';
s += ' * of these tables (libavcodec/sp5xdec.c sp5x_decode_frame) and drops the first/last 2 bytes\n';
s += ' * of every frame, so an AMV frame carries no DQT/DHT/SOF/SOS of its own.\n';
s += ' *\n';
s += ' * Generated file - do not edit by hand. Regenerate with tools/gen-tables.js.\n';
s += ' */\n';
s += 'public final class Sp5xTables {\n';
s += '    private Sp5xTables() {}\n\n';
s += '    /** Zig-zag scan order: index in zig-zag order -> index in natural (row-major) order. */\n';
s += '    public static final int[] ZIGZAG = {\n' +
  '        0, 1, 8, 16, 9, 2, 3, 10,\n' +
  '        17, 24, 32, 25, 18, 11, 4, 5,\n' +
  '        12, 19, 26, 33, 40, 48, 41, 34,\n' +
  '        27, 20, 13, 6, 7, 14, 21, 28,\n' +
  '        35, 42, 49, 56, 57, 50, 43, 36,\n' +
  '        29, 22, 15, 23, 30, 37, 44, 51,\n' +
  '        58, 59, 52, 45, 38, 31, 39, 46,\n' +
  '        53, 60, 61, 54, 47, 55, 62, 63\n    };\n\n';
s += '    /** Quantisation table for component 1 (Y), in DQT (zig-zag) order. sp5x_qscale_five_quant_table[0]. */\n';
s += jArr('QUANT_Y_ZIGZAG', q.slice(0, 64));
s += '\n    /** Quantisation table for components 2 (Cb) and 3 (Cr), in DQT (zig-zag) order. */\n';
s += jArr('QUANT_C_ZIGZAG', q.slice(64, 128));
s += '\n    /** Natural (row-major) order copies, used by the encoder. */\n';
s += '    public static final int[] QUANT_Y = natural(QUANT_Y_ZIGZAG);\n';
s += '    public static final int[] QUANT_C = natural(QUANT_C_ZIGZAG);\n\n';
s += '    private static int[] natural(int[] zigzagOrder) {\n';
s += '        int[] n = new int[64];\n';
s += '        for (int k = 0; k < 64; k++) n[ZIGZAG[k]] = zigzagOrder[k];\n';
s += '        return n;\n';
s += '    }\n\n';
for (const t of tables) {
  const nm = `DHT_${t.tc === 0 ? 'DC' : 'AC'}_${t.th === 0 ? 'LUMA' : 'CHROMA'}`;
  s += `    /** Huffman table ${t.tc === 0 ? 'DC' : 'AC'} ${t.th === 0 ? 'luminance' : 'chrominance'} - BITS (16 counts). */\n`;
  s += jArr(nm + '_BITS', t.counts, 16);
  s += `\n    /** Huffman table ${t.tc === 0 ? 'DC' : 'AC'} ${t.th === 0 ? 'luminance' : 'chrominance'} - HUFFVAL (${t.vals.length} symbols). */\n`;
  s += jArr(nm + '_VALS', t.vals, 16);
  s += '\n';
}
s += '}\n';
fs.mkdirSync(require('path').dirname(out), { recursive: true });
fs.writeFileSync(out, s);
console.log('\nwrote', out, s.length, 'bytes');
