package com.amvconverter.core;

/**
 * Canonical Huffman code table built from a JPEG BITS/HUFFVAL pair.
 */
final class HuffmanTable {
    final int[] code = new int[256];
    final int[] size = new int[256];
    final boolean[] present = new boolean[256];

    HuffmanTable(int[] bits, int[] vals) {
        int k = 0;
        int codeValue = 0;
        for (int len = 1; len <= 16; len++) {
            for (int i = 0; i < bits[len - 1]; i++) {
                int symbol = vals[k++];
                this.code[symbol] = codeValue;
                this.size[symbol] = len;
                this.present[symbol] = true;
                codeValue++;
            }
            codeValue <<= 1;
        }
    }

    boolean has(int symbol) { return present[symbol & 0xFF]; }
    int sizeOf(int symbol) { return size[symbol & 0xFF]; }
    int codeOf(int symbol) { return code[symbol & 0xFF]; }
}
