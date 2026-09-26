package com.amvconverter.core;

/**
 * Separable triangle-filter resampler for YUV 4:2:0, with letterbox / stretch / crop fit modes.
 *
 * <p>Two things this class gets right that a naive scaler does not:
 * <ul>
 *   <li>Decoder output is normally video-range (Y 16..235); AMV frames are JPEG scans and
 *       therefore full-range. Without expansion the converted picture is visibly washed out.</li>
 *   <li>Downscaling 1080p to 160x120 with nearest-neighbour (or an unscaled tent filter)
 *       aliases badly. The filter support is scaled with the reduction ratio.</li>
 * </ul>
 *
 * <p>Geometry and filter weights are computed once by {@link #prepare} and reused for every
 * frame: a conversion uses the same source size, target size and fit for thousands of frames, and
 * rebuilding a few hundred weight arrays per frame was pure overhead.
 *
 * <p>Not thread safe; one instance per conversion.
 */
public final class YuvScaler {

    public enum Fit {
        /** Keep aspect ratio, add black bars. */
        FIT,
        /** Ignore aspect ratio. */
        STRETCH,
        /** Keep aspect ratio, fill the frame and crop the overflow. */
        CROP
    }

    private static final int PRECISION = 14;

    private boolean expandVideoRange = true;
    private int[] tmp;

    // prepared geometry
    private boolean prepared;
    private int srcW, srcH, dstW, dstH;
    private Fit fit;
    private Axis lumaX, lumaY, chromaX, chromaY;
    private int chromaOutStride, chromaOutRows;
    private boolean fillBackground;

    /** Video range (16..235 / 16..240) -> full range. */
    public void setExpandVideoRange(boolean expand) { this.expandVideoRange = expand; }

    public boolean isExpandVideoRange() { return expandVideoRange; }

    /** Computes the resampling geometry once; {@link #scale} then reuses it. */
    public void prepare(int srcWidth, int srcHeight, int dstWidth, int dstHeight, Fit mode) {
        if (prepared && srcW == srcWidth && srcH == srcHeight
                && dstW == dstWidth && dstH == dstHeight && fit == mode) {
            return;
        }
        this.srcW = srcWidth;
        this.srcH = srcHeight;
        this.dstW = dstWidth;
        this.dstH = dstHeight;
        this.fit = mode;

        int sx0, sy0, sw, sh, dx0, dy0, dw, dh;
        fillBackground = false;
        if (mode == Fit.STRETCH) {
            sx0 = 0; sy0 = 0; sw = srcWidth; sh = srcHeight;
            dx0 = 0; dy0 = 0; dw = dstWidth; dh = dstHeight;
        } else if (mode == Fit.CROP) {
            double ratio = Math.min((double) dstWidth / srcWidth, (double) dstHeight / srcHeight);
            int cw = (int) Math.round(dstWidth / ratio);
            int ch = (int) Math.round(dstHeight / ratio);
            if (cw > srcWidth) cw = srcWidth;
            if (ch > srcHeight) ch = srcHeight;
            sx0 = (srcWidth - cw) / 2;
            sy0 = (srcHeight - ch) / 2;
            sw = cw; sh = ch;
            dx0 = 0; dy0 = 0; dw = dstWidth; dh = dstHeight;
        } else {
            double ratio = Math.min((double) dstWidth / srcWidth, (double) dstHeight / srcHeight);
            dw = (int) Math.round(srcWidth * ratio);
            dh = (int) Math.round(srcHeight * ratio);
            if (dw > dstWidth) dw = dstWidth;
            if (dh > dstHeight) dh = dstHeight;
            sx0 = 0; sy0 = 0; sw = srcWidth; sh = srcHeight;
            dx0 = (dstWidth - dw) / 2;
            dy0 = (dstHeight - dh) / 2;
            fillBackground = true;
        }

        lumaX = Axis.build(sx0, sw, dx0, dw);
        lumaY = Axis.build(sy0, sh, dy0, dh);

        int scw = (srcWidth + 1) / 2, sch = (srcHeight + 1) / 2;
        int dcw = (dstWidth + 1) / 2, dch = (dstHeight + 1) / 2;
        int csx = sx0 / 2, csy = sy0 / 2;
        int csw = Math.max(1, sw / 2), csh = Math.max(1, sh / 2);
        int cdx = dx0 / 2, cdy = dy0 / 2;
        int cdw = Math.max(1, (dw + 1) / 2), cdh = Math.max(1, (dh + 1) / 2);
        if (csx + csw > scw) csw = scw - csx;
        if (csy + csh > sch) csh = sch - csy;
        if (cdx + cdw > dcw) cdw = dcw - cdx;
        if (cdy + cdh > dch) cdh = dch - cdy;
        chromaOutStride = dcw;
        chromaOutRows = dch;
        if (csw > 0 && csh > 0 && cdw > 0 && cdh > 0) {
            chromaX = Axis.build(csx, csw, cdx, cdw);
            chromaY = Axis.build(csy, csh, cdy, cdh);
        } else {
            chromaX = null;
            chromaY = null;
        }
        prepared = true;
    }

    /** Convenience for one-off use and tests. */
    public void scale(YuvFrame src, YuvFrame dst, Fit mode) {
        prepare(src.width, src.height, dst.width, dst.height, mode);
        scale(src, dst);
    }

    /** Scales {@code src} into {@code dst} using the geometry from {@link #prepare}. */
    public void scale(YuvFrame src, YuvFrame dst) {
        if (!prepared) {
            prepare(src.width, src.height, dst.width, dst.height, Fit.FIT);
        }
        if (fillBackground) dst.fill(0, 128, 128);
        resamplePlane(src.y, src.width, src.height, dst.y, dst.width, lumaX, lumaY, true);
        if (chromaX != null) {
            int scw = (src.width + 1) / 2;
            int sch = (src.height + 1) / 2;
            int dcw = (dst.width + 1) / 2;
            resamplePlane(src.u, scw, sch, dst.u, dcw, chromaX, chromaY, false);
            resamplePlane(src.v, scw, sch, dst.v, dcw, chromaX, chromaY, false);
        }
    }

    /** One plane, the source is a packed byte array of {@code sStride} bytes per row. */
    private void resamplePlane(byte[] sPlane, int sStride, int sRows,
                               byte[] dPlane, int dStride, Axis hx, Axis vy, boolean luma) {
        int need = sRows * hx.outLen;
        if (tmp == null || tmp.length < need) tmp = new int[need];
        int[] t = tmp;

        // horizontal pass: t[y * hx.outLen + dx]
        for (int y = 0; y < sRows; y++) {
            int srcRow = y * sStride;
            int tRow = y * hx.outLen;
            for (int i = 0; i < hx.outLen; i++) {
                int idx = srcRow + hx.start[i];
                int wp = hx.offset[i], n = hx.count[i];
                int acc = 0;
                for (int k = 0; k < n; k++) {
                    int sv = sPlane[idx + k] & 0xFF;
                    acc += hx.weight[wp + k] * expand(sv, luma);
                }
                t[tRow + i] = acc >> PRECISION;
            }
        }
        // vertical pass
        for (int j = 0; j < vy.outLen; j++) {
            int y0 = vy.start[j], n = vy.count[j], wp = vy.offset[j];
            int dRow = (vy.destStart + j) * dStride + hx.destStart;
            for (int i = 0; i < hx.outLen; i++) {
                int acc = 0;
                for (int k = 0; k < n; k++) {
                    acc += vy.weight[wp + k] * t[(y0 + k) * hx.outLen + i];
                }
                int val = acc >> PRECISION;
                dPlane[dRow + i] = (byte) (val < 0 ? 0 : (val > 255 ? 255 : val));
            }
        }
    }

    private int expand(int v, boolean luma) {
        if (!expandVideoRange) return v;
        if (luma) {
            int r = ((v - 16) * 255 + 109) / 219;
            return r < 0 ? 0 : (r > 255 ? 255 : r);
        }
        int r = ((v - 128) * 255 + 112) / 224 + 128;
        return r < 0 ? 0 : (r > 255 ? 255 : r);
    }

    /** Weight table for one axis: maps an output range onto a source range. */
    private static final class Axis {
        final int outLen;
        final int destStart;
        final int[] start;    // per output sample: first source index to read
        final int[] offset;   // per output sample: start index into weight[]
        final int[] count;    // per output sample: how many source samples
        final int[] weight;

        private Axis(int outLen, int destStart, int[] start, int[] offset, int[] count, int[] weight) {
            this.outLen = outLen;
            this.destStart = destStart;
            this.start = start;
            this.offset = offset;
            this.count = count;
            this.weight = weight;
        }

        static Axis build(int srcStart, int srcLen, int dstStart, int dstLen) {
            int[] start = new int[dstLen];
            int[] offset = new int[dstLen];
            int[] count = new int[dstLen];
            int[] weight = new int[dstLen * 8 + 64];
            double ratio = (double) srcLen / dstLen;
            double support = Math.max(1.0, ratio);
            int wp = 0;
            int[] scratch = new int[(int) Math.ceil(support * 2) + 4];
            for (int i = 0; i < dstLen; i++) {
                double center = srcStart + (i + 0.5) * ratio;
                int lo = (int) Math.floor(center - support);
                int hi = (int) Math.ceil(center + support);
                if (lo < srcStart) lo = srcStart;
                if (hi > srcStart + srcLen) hi = srcStart + srcLen;
                if (hi <= lo) hi = Math.min(lo + 1, srcStart + srcLen);
                int n = hi - lo;
                start[i] = lo;
                offset[i] = wp;
                count[i] = n;
                if (wp + n > weight.length) {
                    int[] bigger = new int[Math.max(weight.length * 2, wp + n + 64)];
                    System.arraycopy(weight, 0, bigger, 0, wp);
                    weight = bigger;
                }
                if (scratch.length < n) scratch = new int[n];
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    double d = Math.abs((lo + k + 0.5) - center) / support;
                    double ww = d >= 1.0 ? 0.0 : (1.0 - d);
                    scratch[k] = (int) Math.round(ww * (1 << PRECISION));
                    sum += scratch[k];
                }
                if (sum <= 0) {
                    for (int k = 0; k < n; k++) weight[wp + k] = 0;
                    weight[wp] = 1 << PRECISION;
                } else {
                    int isum = 0;
                    for (int k = 0; k < n; k++) isum += scratch[k];
                    if (isum <= 0) isum = 1;
                    for (int k = 0; k < n; k++) {
                        weight[wp + k] = (int) ((long) scratch[k] * (1 << PRECISION) / isum);
                    }
                }
                wp += n;
            }
            return new Axis(dstLen, dstStart, start, offset, count, weight);
        }
    }
}
