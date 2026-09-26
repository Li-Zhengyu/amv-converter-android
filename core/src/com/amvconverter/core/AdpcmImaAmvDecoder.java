package com.amvconverter.core;

/**
 * IMA ADPCM ("ADPCM IMA AMV") block decoder - the inverse of {@link AdpcmImaAmvEncoder}.
 *
 * <p>Every block carries its own predictor and step index in the 8-byte header, and the encoder
 * writes the state as it stands at the start of the block, so each block decodes independently
 * and the stream is fully seekable.
 */
public final class AdpcmImaAmvDecoder {

    /** Decodes one block starting at {@code offset}; returns the number of samples produced. */
    public static int decodeBlock(byte[] block, int offset, int length, short[] out, int outOffset) {
        if (length < 8) return 0;
        int predictor = (short) ((block[offset] & 0xFF) | ((block[offset + 1] & 0xFF) << 8));
        int stepIndex = block[offset + 2] & 0xFF;
        long declared = ((long) (block[offset + 4] & 0xFF))
                | ((long) (block[offset + 5] & 0xFF) << 8)
                | ((long) (block[offset + 6] & 0xFF) << 16)
                | ((long) (block[offset + 7] & 0xFF) << 24);
        int available = (length - 8) * 2;
        int samples = (int) Math.min(available, declared <= 0 ? available : declared);
        if (stepIndex > 88) stepIndex = 88;

        int p = offset + 8;
        int written = 0;
        for (int i = 0; i < samples; i++) {
            int b = block[p + (i >> 1)] & 0xFF;
            int nibble = (i & 1) == 0 ? (b >> 4) : (b & 0x0F);
            int step = AdpcmTables.STEP_TABLE[stepIndex];
            stepIndex += AdpcmTables.INDEX_TABLE[nibble];
            if (stepIndex < 0) stepIndex = 0;
            else if (stepIndex > 88) stepIndex = 88;
            int diff = ((2 * (nibble & 7) + 1) * step) >> 3;
            predictor += (nibble & 8) != 0 ? -diff : diff;
            if (predictor > 32767) predictor = 32767;
            else if (predictor < -32768) predictor = -32768;
            out[outOffset + written++] = (short) predictor;
        }
        return written;
    }
}
