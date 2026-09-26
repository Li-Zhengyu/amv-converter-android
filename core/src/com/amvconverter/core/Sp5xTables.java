package com.amvconverter.core;

/**
 * Fixed AMV (sp5x) tables, extracted automatically from FFmpeg libavcodec/sp5x.h.
 *
 * These are NOT negotiable: the AMV decoder synthesises a JPEG header from its own copies
 * of these tables (libavcodec/sp5xdec.c sp5x_decode_frame) and drops the first/last 2 bytes
 * of every frame, so an AMV frame carries no DQT/DHT/SOF/SOS of its own.
 *
 * Generated file - do not edit by hand. Regenerate with tools/gen-tables.js.
 */
public final class Sp5xTables {
    private Sp5xTables() {}

    /** Zig-zag scan order: index in zig-zag order -> index in natural (row-major) order. */
    public static final int[] ZIGZAG = {
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    };

    /** Quantisation table for component 1 (Y), in DQT (zig-zag) order. sp5x_qscale_five_quant_table[0]. */
    public static final int[] QUANT_Y_ZIGZAG = {
        13, 9, 10, 11, 10, 8, 13, 11, 10, 11, 14, 14,
        13, 15, 19, 32, 21, 19, 18, 18, 19, 39, 28, 30,
        23, 32, 46, 41, 49, 48, 46, 41, 45, 44, 51, 58,
        74, 62, 51, 54, 70, 55, 44, 45, 64, 87, 65, 70,
        76, 78, 82, 83, 82, 50, 62, 90, 97, 90, 80, 96,
        74, 81, 82, 79
    };

    /** Quantisation table for components 2 (Cb) and 3 (Cr), in DQT (zig-zag) order. */
    public static final int[] QUANT_C_ZIGZAG = {
        14, 14, 14, 19, 17, 19, 38, 21, 21, 38, 79, 53,
        45, 53, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79,
        79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79,
        79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79,
        79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79, 79,
        79, 79, 79, 79
    };

    /** Natural (row-major) order copies, used by the encoder. */
    public static final int[] QUANT_Y = natural(QUANT_Y_ZIGZAG);
    public static final int[] QUANT_C = natural(QUANT_C_ZIGZAG);

    private static int[] natural(int[] zigzagOrder) {
        int[] n = new int[64];
        for (int k = 0; k < 64; k++) n[ZIGZAG[k]] = zigzagOrder[k];
        return n;
    }

    /** Huffman table DC luminance - BITS (16 counts). */
    public static final int[] DHT_DC_LUMA_BITS = {
        0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0
    };

    /** Huffman table DC luminance - HUFFVAL (12 symbols). */
    public static final int[] DHT_DC_LUMA_VALS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    };

    /** Huffman table DC chrominance - BITS (16 counts). */
    public static final int[] DHT_DC_CHROMA_BITS = {
        0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0
    };

    /** Huffman table DC chrominance - HUFFVAL (12 symbols). */
    public static final int[] DHT_DC_CHROMA_VALS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    };

    /** Huffman table AC luminance - BITS (16 counts). */
    public static final int[] DHT_AC_LUMA_BITS = {
        0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125
    };

    /** Huffman table AC luminance - HUFFVAL (162 symbols). */
    public static final int[] DHT_AC_LUMA_VALS = {
        1, 2, 3, 0, 4, 17, 5, 18, 33, 49, 65, 6, 19, 81, 97, 7,
        34, 113, 20, 50, 129, 145, 161, 8, 35, 66, 177, 193, 21, 82, 209, 240,
        36, 51, 98, 114, 130, 9, 10, 22, 23, 24, 25, 26, 37, 38, 39, 40,
        41, 42, 52, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72, 73,
        74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104, 105,
        106, 115, 116, 117, 118, 119, 120, 121, 122, 131, 132, 133, 134, 135, 136, 137,
        138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165, 166, 167,
        168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195, 196, 197,
        198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218, 225, 226,
        227, 228, 229, 230, 231, 232, 233, 234, 241, 242, 243, 244, 245, 246, 247, 248,
        249, 250
    };

    /** Huffman table AC chrominance - BITS (16 counts). */
    public static final int[] DHT_AC_CHROMA_BITS = {
        0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119
    };

    /** Huffman table AC chrominance - HUFFVAL (162 symbols). */
    public static final int[] DHT_AC_CHROMA_VALS = {
        0, 1, 2, 3, 17, 4, 5, 33, 49, 6, 18, 65, 81, 7, 97, 113,
        19, 34, 50, 129, 8, 20, 66, 145, 161, 177, 193, 9, 35, 51, 82, 240,
        21, 98, 114, 209, 10, 22, 36, 52, 225, 37, 241, 23, 24, 25, 26, 38,
        39, 40, 41, 42, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72,
        73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104,
        105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 130, 131, 132, 133, 134, 135,
        136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165,
        166, 167, 168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195,
        196, 197, 198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218,
        226, 227, 228, 229, 230, 231, 232, 233, 234, 242, 243, 244, 245, 246, 247, 248,
        249, 250
    };

}
