package com.amvconverter.core;

/**
 * A tightly packed planar YUV 4:2:0 (I420) frame in full-range ("JPEG") colour space.
 *
 * <p>AMV frames are JPEG scans, so all YUV handled here is full range: Y in [0,255],
 * U/V in [0,255] with 128 as neutral. Decoder output that uses the video range
 * (Y 16..235) must be expanded before it reaches this class - see
 * {@link YuvScaler#setExpandVideoRange(boolean)}.
 */
public final class YuvFrame {
    public final int width;
    public final int height;
    public final byte[] y;
    public final byte[] u;
    public final byte[] v;

    public YuvFrame(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad size " + width + "x" + height);
        this.width = width;
        this.height = height;
        this.y = new byte[width * height];
        int cw = (width + 1) / 2;
        int ch = (height + 1) / 2;
        this.u = new byte[cw * ch];
        this.v = new byte[cw * ch];
    }

    public int chromaWidth() { return (width + 1) / 2; }
    public int chromaHeight() { return (height + 1) / 2; }

    public void fill(int yVal, int uVal, int vVal) {
        java.util.Arrays.fill(y, (byte) yVal);
        java.util.Arrays.fill(u, (byte) uVal);
        java.util.Arrays.fill(v, (byte) vVal);
    }

    /** Full-range BT.601 YUV -> packed ARGB, used for on-screen preview. */
    public static void yuvToArgb(YuvFrame f, int[] out) {
        int cw = f.chromaWidth();
        for (int j = 0; j < f.height; j++) {
            int yi = j * f.width;
            int ci = (j >> 1) * cw;
            for (int i = 0; i < f.width; i++) {
                int Y = f.y[yi + i] & 0xFF;
                int U = (f.u[ci + (i >> 1)] & 0xFF) - 128;
                int V = (f.v[ci + (i >> 1)] & 0xFF) - 128;
                int r = clamp(Y + ((91881 * V) >> 16));
                int g = clamp(Y - ((22554 * U + 46802 * V) >> 16));
                int b = clamp(Y + ((116130 * U) >> 16));
                out[yi + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int clamp(int x) { return x < 0 ? 0 : (x > 255 ? 255 : x); }

    /** Packed ARGB -> 4:2:0 with a box filter, full-range BT.601. */
    public static void argbToYuv420(int[] argb, int w, int h, YuvFrame dst) {
        int cw = dst.chromaWidth(), ch = dst.chromaHeight();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = argb[j * w + i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int Y = ((77 * r + 150 * g + 29 * b) >> 8);
                dst.y[j * dst.width + i] = (byte) Y;
            }
        }
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                int su = 0, sv = 0, n = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int sy = (j << 1) + dy;
                    if (sy >= h) break;
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = (i << 1) + dx;
                        if (sx >= w) break;
                        int c = argb[sy * w + sx];
                        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                        su += ((-43 * r - 85 * g + 128 * b) >> 8) + 128;
                        sv += ((128 * r - 107 * g - 21 * b) >> 8) + 128;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                dst.u[j * cw + i] = (byte) (su / n);
                dst.v[j * cw + i] = (byte) (sv / n);
            }
        }
    }
}
