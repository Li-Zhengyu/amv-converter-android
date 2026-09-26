// Extract the IMA ADPCM step/index tables from FFmpeg's adpcm_data.c into Java source.
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(process.argv[2], 'utf8');
const out = process.argv[3];

function arr(name) {
  const m = src.match(new RegExp(name + '\\s*\\[[^\\]]*\\]\\s*=\\s*\\{([\\s\\S]*?)\\};'));
  if (!m) throw new Error('not found: ' + name);
  return m[1].replace(/\/\*[\s\S]*?\*\//g, '').replace(/[{}]/g, '')
    .split(',').map(s => s.trim()).filter(Boolean)
    .map(t => t.match(/^0x/i) ? parseInt(t, 16) : parseInt(t, 10));
}

const step = arr('ff_adpcm_step_table');
const index = arr('ff_adpcm_index_table');
console.log('step table:', step.length, 'entries  (expect 89)');
console.log('index table:', index.length, 'entries (expect 16)');
if (step.length !== 89) throw new Error('unexpected step table size');
if (index.length !== 16) throw new Error('unexpected index table size');
console.log('step[0..8] =', step.slice(0, 9).join(','), ' step[88] =', step[88]);
console.log('index =', index.join(','));

function jArr(name, nums, perLine) {
  const lines = [];
  for (let i = 0; i < nums.length; i += perLine) {
    lines.push('        ' + nums.slice(i, i + perLine).join(', ') + (i + perLine < nums.length ? ',' : ''));
  }
  return `    public static final int[] ${name} = {\n${lines.join('\n')}\n    };\n`;
}

let s = '';
s += 'package com.amvconverter.core;\n\n';
s += '/**\n';
s += ' * IMA ADPCM tables, extracted automatically from FFmpeg libavcodec/adpcm_data.c\n';
s += ' * (ff_adpcm_step_table / ff_adpcm_index_table).\n';
s += ' *\n';
s += ' * Generated file - do not edit by hand. Regenerate with tools/gen-adpcm.js.\n';
s += ' */\n';
s += 'public final class AdpcmTables {\n';
s += '    private AdpcmTables() {}\n\n';
s += '    /** 89 entries: valid step_index range is 0..88. */\n';
s += jArr('STEP_TABLE', step, 10);
s += '\n    /** 16 entries, indexed by the raw 4-bit nibble whose bit 3 is the sign. */\n';
s += jArr('INDEX_TABLE', index, 8);
s += '}\n';

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, s);
console.log('\nwrote', out);
