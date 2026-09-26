package com.amvconverter.core;

/**
 * IMA ADPCM tables, extracted automatically from FFmpeg libavcodec/adpcm_data.c
 * (ff_adpcm_step_table / ff_adpcm_index_table).
 *
 * Generated file - do not edit by hand. Regenerate with tools/gen-adpcm.js.
 */
public final class AdpcmTables {
    private AdpcmTables() {}

    /** 89 entries: valid step_index range is 0..88. */
    public static final int[] STEP_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    /** 16 entries, indexed by the raw 4-bit nibble whose bit 3 is the sign. */
    public static final int[] INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };
}
