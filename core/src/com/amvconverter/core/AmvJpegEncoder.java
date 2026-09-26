package com.amvconverter.core;

/**
 * Baseline JPEG entropy encoder producing AMV video frames.
 *
 * <p>An AMV frame is deliberately not a usable JPEG file. FFmpeg's AMV decoder
 * (libavcodec/sp5xdec.c) throws away the first two and last two bytes of every packet and
 * re-wraps the remaining bytes in a JPEG header it builds itself from fixed tables. A frame is:
 *
 * <pre>
 *   FF D8  &lt;baseline entropy data&gt;  FF D9
 * </pre>
 *
 * <p>The entropy data must therefore be coded exactly as the decoder's fixed tables expect:
 * the sp5x Q60 quantisation tables and the standard Annex K Huffman tables (verified
 * byte-identical to FFmpeg's own copies, and complete for every baseline symbol). A frame
 * carries no DQT/DHT/SOF/SOS and no restart marker. The image is stored vertically flipped,
 * because the decoder flips it back.
 *
 * <p>The quantisation table is fixed by the format, so quality is not adjustable in the usual
 * sense; {@link #setQuantScale(double)} can only make the encoder quantise coarser than the
 * decoder's table, trading picture quality for a smaller file.
 *
 * <p>Not thread safe: one instance per conversion, used from a single thread.
 */
public final class AmvJpegEncoder {

    private static final int MCU = 16;

    private final int width;
    private final int height;
    private final int mcuCols;
    private final int mcuRows;
    private final int paddedWidth;
    private final int paddedHeight;
    private final int cw;
    private final int ch;

    private final byte[] py;
    private final byte[] pu;
    private final byte[] pv;

    private final int[] quantY = new int[64];
    private final int[] quantC = new int[64];
    private double quantScale = 1.0;

    private final HuffmanTable dcLuma, acLuma, dcChroma, acChroma;

    private final float[] cos = new float[64];
    private final float[] c = new float[8];
    private final float[] blk = new float[64];
    private final float[] rowTmp = new float[64];
    private final int[] coef = new int[64];
    private final int[] zz = new int[64];

    private byte[] out = new byte[16384];
    private int outLen;
    private int bitBuf;
    private int bitCount;
    private int dcY, dcCb, dcCr;

    public AmvJpegEncoder(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad frame size");
        this.width = width;
        this.height = height;
        this.mcuCols = (width + MCU - 1) / MCU;
        this.mcuRows = (height + MCU - 1) / MCU;
        this.paddedWidth = mcuCols * MCU;
        this.paddedHeight = mcuRows * MCU;
        this.cw = paddedWidth / 2;
        this.ch = paddedHeight / 2;
        this.py = new byte[paddedWidth * paddedHeight];
        this.pu = new byte[cw * ch];
        this.pv = new byte[cw * ch];

        System.arraycopy(Sp5xTables.QUANT_Y, 0, quantY, 0, 64);
        System.arraycopy(Sp5xTables.QUANT_C, 0, quantC, 0, 64);

        dcLuma = new HuffmanTable(Sp5xTables.DHT_DC_LUMA_BITS, Sp5xTables.DHT_DC_LUMA_VALS);
        dcChroma = new HuffmanTable(Sp5xTables.DHT_DC_CHROMA_BITS, Sp5xTables.DHT_DC_CHROMA_VALS);
        acLuma = new HuffmanTable(Sp5xTables.DHT_AC_LUMA_BITS, Sp5xTables.DHT_AC_LUMA_VALS);
        acChroma = new HuffmanTable(Sp5xTables.DHT_AC_CHROMA_BITS, Sp5xTables.DHT_AC_CHROMA_VALS);

        for (int u = 0; u < 8; u++) {
            c[u] = (u == 0) ? (float) (1.0 / Math.sqrt(2.0)) : 1.0f;
            for (int x = 0; x < 8; x++) {
                cos[u * 8 + x] = (float) Math.cos((2 * x + 1) * u * Math.PI / 16.0);
            }
        }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setQuantScale(double scale) {
        if (scale < 1.0) scale = 1.0;
        if (scale > 8.0) scale = 8.0;
        this.quantScale = scale;
    }

    public double getQuantScale() { return quantScale; }

    /** Bytes of the most recently encoded frame; valid after {@link #encode}. */
    public byte[] frameBuffer() { return out; }

    /** Encoded length of the most recent frame including the SOI/EOI wrappers. */
    public int frameLength() { return outLen; }

    /**
     * Encodes one frame. {@code frame} must be {@link #getWidth()} x {@link #getHeight()} and in
     * display order; the vertical flip the format requires happens here.
     *
     * @return the encoded frame length in bytes
     */
    public int encode(YuvFrame frame) {
        if (frame.width != width || frame.height != height) {
            throw new IllegalArgumentException("frame is " + frame.width + "x" + frame.height
                    + ", encoder is " + width + "x" + height);
        }
        copyFlippedAndPad(frame);
        outLen = 0;
        bitBuf = 0;
        bitCount = 0;
        dcY = dcCb = dcCr = 0;
        // The SOI/EOI markers are raw bytes: only the entropy data is byte-stuffed, otherwise
        // the markers themselves would turn into FF 00 D8 / FF 00 D9 and the decoder (which
        // copies everything between the first and last two bytes into its own JPEG) would
        // produce a corrupt scan.
        emitRaw(0xFF);
        emitRaw(0xD8);

        for (int my = 0; my < mcuRows; my++) {
            for (int mx = 0; mx < mcuCols; mx++) {
                int bx = mx * MCU, by = my * MCU;
                encodeBlock(py, paddedWidth, bx, by, quantY, 0);
                encodeBlock(py, paddedWidth, bx + 8, by, quantY, 0);
                encodeBlock(py, paddedWidth, bx, by + 8, quantY, 0);
                encodeBlock(py, paddedWidth, bx + 8, by + 8, quantY, 0);
                encodeBlock(pu, cw, mx * 8, my * 8, quantC, 1);
                encodeBlock(pv, cw, mx * 8, my * 8, quantC, 2);
            }
        }

        flushBits();
        emitRaw(0xFF);
        emitRaw(0xD9);
        return outLen;
    }

    /**
     * Copies the display-order source into the working planes, applying the vertical flip the
     * format requires and replicating edge pixels out to the MCU boundary.
     *
     * <p>The placement of the pad rows is subtle and getting it wrong shifts the whole picture.
     * A frame is stored bottom-up, so stored row {@code y} holds display row {@code height-1-y}.
     * When the height is not a multiple of 16 the decoder keeps the first {@code height} rows of
     * the decoded image and flips them, so the pad has to sit at the <em>end</em> of the stored
     * image (replicating display row 0) where it is discarded - not at the start, which would
     * push the real picture down by the pad size.
     */
    private void copyFlippedAndPad(YuvFrame s) {
        int sw = s.width, sh = s.height;
        int scw = s.chromaWidth(), sch = s.chromaHeight();
        for (int y = 0; y < paddedHeight; y++) {
            int sy = sh - 1 - y;
            if (sy < 0) sy = 0;
            int so = sy * sw;
            int drow = y * paddedWidth;
            for (int x = 0; x < paddedWidth; x++) {
                py[drow + x] = s.y[so + (x < sw ? x : sw - 1)];
            }
        }
        for (int y = 0; y < ch; y++) {
            int sy = sch - 1 - y;
            if (sy < 0) sy = 0;
            int so = sy * scw;
            int drow = y * cw;
            for (int x = 0; x < cw; x++) {
                int sx = x < scw ? x : scw - 1;
                pu[drow + x] = s.u[so + sx];
                pv[drow + x] = s.v[so + sx];
            }
        }
    }

    /**
     * @param component 0 = Y, 1 = Cb, 2 = Cr
     */
    private void encodeBlock(byte[] plane, int stride, int px, int py0, int[] quant, int component) {
        for (int y = 0; y < 8; y++) {
            int row = (py0 + y) * stride + px;
            for (int x = 0; x < 8; x++) {
                blk[y * 8 + x] = (plane[row + x] & 0xFF) - 128;
            }
        }
        for (int y = 0; y < 8; y++) {
            int base = y * 8;
            for (int u = 0; u < 8; u++) {
                float sum = 0;
                int cb = u * 8;
                for (int x = 0; x < 8; x++) sum += blk[base + x] * cos[cb + x];
                rowTmp[base + u] = sum;
            }
        }
        for (int v = 0; v < 8; v++) {
            int cbv = v * 8;
            float cv = 0.25f * c[v];
            for (int u = 0; u < 8; u++) {
                float sum = 0;
                for (int y = 0; y < 8; y++) sum += rowTmp[y * 8 + u] * cos[cbv + y];
                float val = cv * c[u] * sum;
                int qi = v * 8 + u;
                int q = (int) Math.round(quant[qi] * quantScale);
                if (q < 1) q = 1;
                coef[qi] = roundHalfAway(val / q);
            }
        }
        for (int k = 0; k < 64; k++) {
            int v = coef[Sp5xTables.ZIGZAG[k]];
            zz[k] = v > 1023 ? 1023 : (v < -1023 ? -1023 : v);
        }

        int prevDc = component == 0 ? dcY : (component == 1 ? dcCb : dcCr);
        if (component == 0) dcY = zz[0]; else if (component == 1) dcCb = zz[0]; else dcCr = zz[0];
        emitDc(zz[0] - prevDc, component == 0 ? dcLuma : dcChroma);

        HuffmanTable ac = component == 0 ? acLuma : acChroma;
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int v = zz[k];
            if (v == 0) { run++; continue; }
            while (run >= 16) {
                emitBits(ac.codeOf(0xF0), ac.sizeOf(0xF0));
                run -= 16;
            }
            int symbol = (run << 4) | bitLength(v);
            emitBits(ac.codeOf(symbol), ac.sizeOf(symbol));
            emitSigned(v, bitLength(v));
            run = 0;
        }
        if (run > 0) emitBits(ac.codeOf(0x00), ac.sizeOf(0x00));
    }

    private void emitDc(int value, HuffmanTable table) {
        int size = value == 0 ? 0 : bitLength(value);
        emitBits(table.codeOf(size), table.sizeOf(size));
        if (size != 0) emitSigned(value, size);
    }

    private void emitSigned(int value, int size) {
        if (value >= 0) emitBits(value, size);
        else emitBits(value + (1 << size) - 1, size);
    }

    private static int roundHalfAway(float v) {
        return (int) (v >= 0 ? Math.floor(v + 0.5f) : Math.ceil(v - 0.5f));
    }

    private static int bitLength(int v) {
        if (v < 0) v = -v;
        return 32 - Integer.numberOfLeadingZeros(v);
    }

    private void emitBits(int value, int bits) {
        if (bits <= 0) return;
        bitBuf = (bitBuf << bits) | (value & ((1 << bits) - 1));
        bitCount += bits;
        while (bitCount >= 8) {
            bitCount -= 8;
            emitByte((bitBuf >> bitCount) & 0xFF);
        }
        bitBuf &= (1 << bitCount) - 1;
    }

    /** Writes one byte with no stuffing (used for the SOI/EOI markers). */
    private void emitRaw(int b) {
        ensureCapacity();
        out[outLen++] = (byte) b;
    }

    /** JPEG byte stuffing: a 0xFF in the entropy data must be followed by 0x00. */
    private void emitByte(int b) {
        ensureCapacity();
        out[outLen++] = (byte) b;
        if (b == 0xFF) out[outLen++] = 0x00;
    }

    private void ensureCapacity() {
        if (outLen + 2 >= out.length) {
            byte[] bigger = new byte[out.length * 2];
            System.arraycopy(out, 0, bigger, 0, outLen);
            out = bigger;
        }
    }

    /** Pads the final partial byte with 1 bits, as FFmpeg's encoder does. */
    private void flushBits() {
        if (bitCount > 0) {
            int pad = 8 - bitCount;
            emitBits((1 << pad) - 1, pad);
        }
        bitBuf = 0;
        bitCount = 0;
    }

    // ---------------------------------------------------------------- preview support

    /**
     * Rebuilds a decodable JPEG from an AMV frame, exactly the way a hardware player (and
     * FFmpeg's sp5x decoder) does. Used to show a truthful preview of the encoded result.
     */
    public static byte[] wrapAsJpeg(byte[] frame, int frameLen, int width, int height) {
        int body = frameLen - 4;
        byte[] dqt = buildDqt();
        byte[] dht = buildDht();
        byte[] sof = buildSof(width, height);
        byte[] jpeg = new byte[2 + dqt.length + dht.length + sof.length + SOS.length + body + 2];
        int p = 0;
        jpeg[p++] = (byte) 0xFF; jpeg[p++] = (byte) 0xD8;
        System.arraycopy(dqt, 0, jpeg, p, dqt.length); p += dqt.length;
        System.arraycopy(dht, 0, jpeg, p, dht.length); p += dht.length;
        System.arraycopy(sof, 0, jpeg, p, sof.length); p += sof.length;
        System.arraycopy(SOS, 0, jpeg, p, SOS.length); p += SOS.length;
        System.arraycopy(frame, 2, jpeg, p, body); p += body;
        jpeg[p++] = (byte) 0xFF; jpeg[p] = (byte) 0xD9;
        return jpeg;
    }

    private static final byte[] SOS = {
            (byte) 0xFF, (byte) 0xDA, 0x00, 0x0C, 0x03,
            0x01, 0x00, 0x02, 0x11, 0x03, 0x11,
            0x00, 0x3F, 0x00
    };

    private static byte[] buildSof(int w, int h) {
        return new byte[]{
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,
                (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w,
                0x03,
                0x01, 0x22, 0x00,
                0x02, 0x11, 0x01,
                0x03, 0x11, 0x01
        };
    }

    private static byte[] buildDqt() {
        byte[] b = new byte[4 + 65 + 65];
        int p = 0;
        b[p++] = (byte) 0xFF; b[p++] = (byte) 0xDB;
        b[p++] = 0x00; b[p++] = (byte) 0x84;
        b[p++] = 0x00;
        for (int i = 0; i < 64; i++) b[p++] = (byte) Sp5xTables.QUANT_Y_ZIGZAG[i];
        b[p++] = 0x01;
        for (int i = 0; i < 64; i++) b[p++] = (byte) Sp5xTables.QUANT_C_ZIGZAG[i];
        return b;
    }

    private static byte[] buildDht() {
        int[][] spec = {
                Sp5xTables.DHT_DC_LUMA_BITS, Sp5xTables.DHT_DC_LUMA_VALS,
                Sp5xTables.DHT_DC_CHROMA_BITS, Sp5xTables.DHT_DC_CHROMA_VALS,
                Sp5xTables.DHT_AC_LUMA_BITS, Sp5xTables.DHT_AC_LUMA_VALS,
                Sp5xTables.DHT_AC_CHROMA_BITS, Sp5xTables.DHT_AC_CHROMA_VALS,
        };
        int tables = spec.length / 2;
        int payload = 0;
        for (int t = 0; t < tables; t++) payload += 1 + 16 + spec[t * 2 + 1].length;
        // The JPEG segment length field counts itself: writing the payload length here instead
        // makes every decoder mis-read the tables (ffmpeg reports "huffman table decode error").
        int len = payload + 2;
        byte[] b = new byte[2 + len];
        int p = 0;
        b[p++] = (byte) 0xFF; b[p++] = (byte) 0xC4;
        b[p++] = (byte) (len >> 8); b[p++] = (byte) len;
        for (int t = 0; t < tables; t++) {
            int[] bits = spec[t * 2];
            int[] vals = spec[t * 2 + 1];
            boolean ac = t >= 2;
            int id = (t % 2 == 0) ? 0 : 1;
            b[p++] = (byte) ((ac ? 0x10 : 0x00) | id);
            for (int i = 0; i < 16; i++) b[p++] = (byte) bits[i];
            for (int v : vals) b[p++] = (byte) v;
        }
        return b;
    }
}
