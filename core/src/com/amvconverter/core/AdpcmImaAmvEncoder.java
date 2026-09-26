package com.amvconverter.core;

/**
 * IMA ADPCM ("ADPCM IMA AMV") block encoder, 22050 Hz mono.
 *
 * <p>Block layout (little endian, 8 byte header then 4-bit nibbles, high nibble first):
 * <pre>
 *   +0  int16  predictor    first PCM sample of this block
 *   +2  uint8  step_index   carried over from the previous block
 *   +3  uint8  reserved     0
 *   +4  uint32 sample count
 *   +8  nibbles
 * </pre>
 *
 * <p>FFmpeg resets {@code predictor} to the block's first sample but lets {@code step_index}
 * run across blocks (adpcmenc.c), and the decoder re-reads both from every block header, so
 * the running state is exactly reproduced.
 */
public final class AdpcmImaAmvEncoder {

    /** Sign-extended nibble magnitude deltas, indexed by the raw nibble (bit 3 = sign). */
    private static final int[] DIFF_LOOKUP = {
            1, 3, 5, 7, 9, 11, 13, 15,
            -1, -3, -5, -7, -9, -11, -13, -15
    };

    private int predictor;
    private int stepIndex;

    public void reset() {
        predictor = 0;
        stepIndex = 0;
    }

    public int getPredictor() { return predictor; }

    public int getStepIndex() { return stepIndex; }

    /** Bytes a block of {@code samples} samples occupies. */
    public static int blockSize(int samples) {
        return 8 + (samples + 1) / 2;
    }

    /**
     * Encodes one block into {@code out} at {@code outOffset}.
     *
     * @return the number of bytes written
     */
    public int encodeBlock(short[] pcm, int offset, int samples, byte[] out, int outOffset) {
        if (samples <= 0) throw new IllegalArgumentException("empty block");
        predictor = pcm[offset];
        int p = outOffset;
        out[p++] = (byte) (predictor & 0xFF);
        out[p++] = (byte) ((predictor >> 8) & 0xFF);
        out[p++] = (byte) stepIndex;
        out[p++] = 0;
        out[p++] = (byte) (samples & 0xFF);
        out[p++] = (byte) ((samples >> 8) & 0xFF);
        out[p++] = (byte) ((samples >> 16) & 0xFF);
        out[p++] = (byte) ((samples >> 24) & 0xFF);

        int n = samples >> 1;
        for (int i = 0; i < n; i++) {
            int hi = compress(pcm[offset++]);
            int lo = compress(pcm[offset++]);
            out[p++] = (byte) ((hi << 4) | lo);
        }
        if ((samples & 1) != 0) {
            int hi = compress(pcm[offset]);
            out[p++] = (byte) (hi << 4);
        }
        return p - outOffset;
    }

    /** Writes an all-silence padding block, matching FFmpeg's dummy packet. */
    public static int writeSilenceBlock(int samples, byte[] out, int outOffset) {
        int p = outOffset;
        out[p++] = 0; out[p++] = 0;         // predictor 0
        out[p++] = 0;                        // step_index 0
        out[p++] = 0;                        // reserved
        out[p++] = (byte) (samples & 0xFF);
        out[p++] = (byte) ((samples >> 8) & 0xFF);
        out[p++] = (byte) ((samples >> 16) & 0xFF);
        out[p++] = (byte) ((samples >> 24) & 0xFF);
        for (int i = 0; i < (samples + 1) / 2; i++) out[p++] = 0;
        return p - outOffset;
    }

    private int compress(int sample) {
        int step = AdpcmTables.STEP_TABLE[stepIndex];
        int delta = sample - predictor;
        int mag = delta < 0 ? -delta : delta;
        int nibble = (mag * 4 / step);
        if (nibble > 7) nibble = 7;
        if (delta < 0) nibble |= 8;

        predictor += (step * DIFF_LOOKUP[nibble]) / 8;
        if (predictor > 32767) predictor = 32767;
        else if (predictor < -32768) predictor = -32768;

        stepIndex += AdpcmTables.INDEX_TABLE[nibble];
        if (stepIndex < 0) stepIndex = 0;
        else if (stepIndex > 88) stepIndex = 88;

        return nibble;
    }
}
